/**
  * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
  * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
  * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
  * License. You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
  * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
  * specific language governing permissions and limitations under the License.
  */
package kafka.admin

import java.util.Collections
import java.util.Properties

import kafka.admin.ReassignPartitionsCommand._
import kafka.common.{AdminCommandFailedException, TopicAndPartition}
import kafka.server.{KafkaConfig, KafkaServer}
import kafka.utils.TestUtils._
import kafka.utils.ZkUtils._
import kafka.utils.{Logging, TestUtils, ZkUtils}
import kafka.zk.ZooKeeperTestHarness
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.{After, Before, Test}
import kafka.admin.ReplicationQuotaUtils._
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.{AdminClient => JAdminClient}
import org.apache.kafka.common.{TopicPartition, TopicPartitionReplica}

import scala.collection.JavaConverters._
import scala.collection.Map
import scala.collection.Seq
import scala.util.Random
import java.io.File

class ReassignPartitionsClusterTest extends ZooKeeperTestHarness with Logging {
  val partitionId = 0
  var servers: Seq[KafkaServer] = null
  val topicName = "my-topic"
  val delayMs = 1000
  var adminClient: JAdminClient = null

  def zkUpdateDelay(): Unit = Thread.sleep(delayMs)

  @Before
  override def setUp() {
    super.setUp()
  }

  def startBrokers(brokerIds: Seq[Int]) {
    servers = brokerIds.map(i => createBrokerConfig(i, zkConnect, enableControlledShutdown = false, logDirCount = 3))
      .map(c => createServer(KafkaConfig.fromProps(c)))
  }

  def createAdminClient(servers: Seq[KafkaServer]): JAdminClient = {
    val props = new Properties()
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, TestUtils.getBrokerListStrFromServers(servers))
    props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000")
    JAdminClient.create(props)
  }

  def getRandomLogDirAssignment(brokerId: Int): String = {
    val server = servers.find(_.config.brokerId == brokerId).get
    val logDirs = server.config.logDirs
    new File(logDirs(Random.nextInt(logDirs.size))).getAbsolutePath
  }

  @After
  override def tearDown() {
    if (adminClient != null) {
      adminClient.close()
      adminClient = null
    }
    TestUtils.shutdownServers(servers)
    super.tearDown()
  }

  @Test
  def shouldMoveSinglePartition(): Unit = {
    //Given a single replica on server 100
    startBrokers(Seq(100, 101))
    adminClient = createAdminClient(servers)
    val partition = 0
    // Get a random log directory on broker 101
    val expectedLogDir = getRandomLogDirAssignment(101)
    createTopic(zkUtils, topicName, Map(partition -> Seq(100)), servers = servers)

    //When we move the replica on 100 to broker 101
    val topicJson: String = s"""{"version":1,"partitions":[{"topic":"$topicName","partition":0,"replicas":[101],"log_dirs":["$expectedLogDir"]}]}"""
    ReassignPartitionsCommand.executeAssignment(zkUtils, Some(adminClient), topicJson, NoThrottle)
    waitForReassignmentToComplete()

    //Then the replica should be on 101
    assertEquals(Seq(101), zkUtils.getPartitionAssignmentForTopics(Seq(topicName)).get(topicName).get(partition))
    // The replica should be in the expected log directory on broker 101
    val replica = new TopicPartitionReplica(topicName, 0, 101)
    assertEquals(expectedLogDir, adminClient.describeReplicaLogDirs(Collections.singleton(replica)).all().get.get(replica).getCurrentReplicaLogDir)
  }

  @Test
  def shouldMoveSinglePartitionWithinBroker() {
    // Given a single replica on server 100
    startBrokers(Seq(100, 101))
    adminClient = createAdminClient(servers)
    val expectedLogDir = getRandomLogDirAssignment(100)
    createTopic(zkUtils, topicName, Map(0 -> Seq(100)), servers = servers)

    // When we execute an assignment that moves an existing replica to another log directory on the same broker
    val topicJson: String = s"""{"version":1,"partitions":[{"topic":"$topicName","partition":0,"replicas":[100],"log_dirs":["$expectedLogDir"]}]}"""
    ReassignPartitionsCommand.executeAssignment(zkUtils, Some(adminClient), topicJson, NoThrottle)
    val replica = new TopicPartitionReplica(topicName, 0, 100)
    TestUtils.waitUntilTrue(() => {
      expectedLogDir == adminClient.describeReplicaLogDirs(Collections.singleton(replica)).all().get.get(replica).getCurrentReplicaLogDir
    }, "Partition should have been moved to the expected log directory", 1000)
  }

  @Test
  def shouldExpandCluster() {
    val brokers = Array(100, 101, 102)
    startBrokers(brokers)
    adminClient = createAdminClient(servers)
    createTopic(zkUtils, topicName, Map(
      0 -> Seq(100, 101),
      1 -> Seq(100, 101),
      2 -> Seq(100, 101)
    ), servers = servers)

    //When rebalancing
    val newAssignment = generateAssignment(zkUtils, brokers, json(topicName), true)._1
    // Find a partition in the new assignment on broker 102 and a random log directory on broker 102,
    // which currently does not have any partition for this topic
    val partition1 = newAssignment.find { case (_, brokerIds) => brokerIds.contains(102) }.get._1.partition
    val replica1 = new TopicPartitionReplica(topicName, partition1, 102)
    val expectedLogDir1 = getRandomLogDirAssignment(102)
    // Find a partition in the new assignment on broker 100 and a random log directory on broker 100,
    // which currently has partition for this topic
    val partition2 = newAssignment.find { case (_, brokerIds) => brokerIds.contains(100) }.get._1.partition
    val replica2 = new TopicPartitionReplica(topicName, partition2, 100)
    val expectedLogDir2 = getRandomLogDirAssignment(100)
    // Generate a replica assignment to reassign replicas on broker 100 and 102 respectively to a random log directory on the same broker.
    // Before this reassignment, the replica already exists on broker 100 but does not exist on broker 102
    val newReplicaAssignment = Map(replica1 -> expectedLogDir1, replica2 -> expectedLogDir2)
    ReassignPartitionsCommand.executeAssignment(zkUtils, Some(adminClient),
      ReassignPartitionsCommand.formatAsReassignmentJson(newAssignment, newReplicaAssignment), NoThrottle)
    waitForReassignmentToComplete()

    // Then the replicas should span all three brokers
    val actual = zkUtils.getPartitionAssignmentForTopics(Seq(topicName))(topicName)
    assertEquals(Seq(100, 101, 102), actual.values.flatten.toSeq.distinct.sorted)
    // The replica should be in the expected log directory on broker 102 and 100
    waitUntilTrue(() => {
      expectedLogDir1 == adminClient.describeReplicaLogDirs(Collections.singleton(replica1)).all().get.get(replica1).getCurrentReplicaLogDir
    }, "Partition should have been moved to the expected log directory on broker 102", 1000)
    waitUntilTrue(() => {
      expectedLogDir2 == adminClient.describeReplicaLogDirs(Collections.singleton(replica2)).all().get.get(replica2).getCurrentReplicaLogDir
    }, "Partition should have been moved to the expected log directory on broker 100", 1000)
  }

  @Test
  def shouldShrinkCluster() {
    //Given partitions on 3 of 3 brokers
    val brokers = Array(100, 101, 102)
    startBrokers(brokers)
    createTopic(zkUtils, topicName, Map(
      0 -> Seq(100, 101),
      1 -> Seq(101, 102),
      2 -> Seq(102, 100)
    ), servers = servers)

    //When rebalancing
    val newAssignment = generateAssignment(zkUtils, Array(100, 101), json(topicName), true)._1
    ReassignPartitionsCommand.executeAssignment(zkUtils, None,
      ReassignPartitionsCommand.formatAsReassignmentJson(newAssignment, Map.empty), NoThrottle)
    waitForReassignmentToComplete()

    //Then replicas should only span the first two brokers
    val actual = zkUtils.getPartitionAssignmentForTopics(Seq(topicName))(topicName)
    assertEquals(Seq(100, 101), actual.values.flatten.toSeq.distinct.sorted)
  }

  @Test
  def shouldMoveSubsetOfPartitions() {
    //Given partitions on 3 of 3 brokers
    val brokers = Array(100, 101, 102)
    startBrokers(brokers)
    adminClient = createAdminClient(servers)
    createTopic(zkUtils, "topic1", Map(
      0 -> Seq(100, 101),
      1 -> Seq(101, 102),
      2 -> Seq(102, 100)
    ), servers = servers)
    createTopic(zkUtils, "topic2", Map(
      0 -> Seq(100, 101),
      1 -> Seq(101, 102),
      2 -> Seq(102, 100)
    ), servers = servers)

    val proposed: Map[TopicAndPartition, Seq[Int]] = Map(
      TopicAndPartition("topic1", 0) -> Seq(100, 102),
      TopicAndPartition("topic1", 2) -> Seq(100, 102),
      TopicAndPartition("topic2", 1) -> Seq(101, 100),
      TopicAndPartition("topic2", 2) -> Seq(100, 102)
    )

    val replica1 = new TopicPartitionReplica("topic1", 0, 102)
    val replica2 = new TopicPartitionReplica("topic2", 1, 100)
    val proposedReplicaAssignment: Map[TopicPartitionReplica, String] = Map(
      replica1 -> getRandomLogDirAssignment(102),
      replica2 -> getRandomLogDirAssignment(100)
    )

    //When rebalancing
    ReassignPartitionsCommand.executeAssignment(zkUtils, Some(adminClient),
      ReassignPartitionsCommand.formatAsReassignmentJson(proposed, proposedReplicaAssignment), NoThrottle)
    waitForReassignmentToComplete()

    //Then the proposed changes should have been made
    val actual = zkUtils.getPartitionAssignmentForTopics(Seq("topic1", "topic2"))
    assertEquals(Seq(100, 102), actual("topic1")(0))//changed
    assertEquals(Seq(101, 102), actual("topic1")(1))
    assertEquals(Seq(100, 102), actual("topic1")(2))//changed
    assertEquals(Seq(100, 101), actual("topic2")(0))
    assertEquals(Seq(101, 100), actual("topic2")(1))//changed
    assertEquals(Seq(100, 102), actual("topic2")(2))//changed

    // The replicas should be in the expected log directories
    val replicaDirs = adminClient.describeReplicaLogDirs(List(replica1, replica2).asJava).all().get()
    assertEquals(proposedReplicaAssignment(replica1), replicaDirs.get(replica1).getCurrentReplicaLogDir)
    assertEquals(proposedReplicaAssignment(replica2), replicaDirs.get(replica2).getCurrentReplicaLogDir)
  }

  @Test
  def shouldExecuteThrottledReassignment() {

    //Given partitions on 3 of 3 brokers
    val brokers = Array(100, 101, 102)
    startBrokers(brokers)
    createTopic(zkUtils, topicName, Map(
      0 -> Seq(100, 101)
    ), servers = servers)

    //Given throttle set so replication will take a certain number of secs
    val initialThrottle = Throttle(10 * 1000 * 1000, -1, () => zkUpdateDelay)
    val expectedDurationSecs = 5
    val numMessages: Int = 500
    val msgSize: Int = 100 * 1000
    produceMessages(servers, topicName, numMessages, acks = 0, msgSize)
    assertEquals(expectedDurationSecs, numMessages * msgSize / initialThrottle.interBrokerLimit)

    //Start rebalance which will move replica on 100 -> replica on 102
    val newAssignment = generateAssignment(zkUtils, Array(101, 102), json(topicName), true)._1

    val start = System.currentTimeMillis()
    ReassignPartitionsCommand.executeAssignment(zkUtils, None,
      ReassignPartitionsCommand.formatAsReassignmentJson(newAssignment, Map.empty), initialThrottle)

    //Check throttle config. Should be throttling replica 0 on 100 and 102 only.
    checkThrottleConfigAddedToZK(initialThrottle.interBrokerLimit, servers, topicName, "0:100,0:101", "0:102")

    //Await completion
    waitForReassignmentToComplete()
    val took = System.currentTimeMillis() - start - delayMs

    //Check move occurred
    val actual = zkUtils.getPartitionAssignmentForTopics(Seq(topicName))(topicName)
    assertEquals(Seq(101, 102), actual.values.flatten.toSeq.distinct.sorted)

    //Then command should have taken longer than the throttle rate
    assertTrue(s"Expected replication to be > ${expectedDurationSecs * 0.9 * 1000} but was $took",
      took > expectedDurationSecs * 0.9 * 1000)
    assertTrue(s"Expected replication to be < ${expectedDurationSecs * 2 * 1000} but was $took",
      took < expectedDurationSecs * 2 * 1000)
  }


  @Test
  def shouldOnlyThrottleMovingReplicas() {
    //Given 6 brokers, two topics
    val brokers = Array(100, 101, 102, 103, 104, 105)
    startBrokers(brokers)
    createTopic(zkUtils, "topic1", Map(
      0 -> Seq(100, 101),
      1 -> Seq(100, 101),
      2 -> Seq(103, 104) //will leave in place
    ), servers = servers)

    createTopic(zkUtils, "topic2", Map(
      0 -> Seq(104, 105),
      1 -> Seq(104, 105),
      2 -> Seq(103, 104)//will leave in place
    ), servers = servers)

    //Given throttle set so replication will take a while
    val throttle: Long = 1000 * 1000
    produceMessages(servers, "topic1", 100, acks = 0, 100 * 1000)
    produceMessages(servers, "topic2", 100, acks = 0, 100 * 1000)

    //Start rebalance
    val newAssignment = Map(
      TopicAndPartition("topic1", 0) -> Seq(100, 102),//moved 101=>102
      TopicAndPartition("topic1", 1) -> Seq(100, 102),//moved 101=>102
      TopicAndPartition("topic2", 0) -> Seq(103, 105),//moved 104=>103
      TopicAndPartition("topic2", 1) -> Seq(103, 105),//moved 104=>103
      TopicAndPartition("topic1", 2) -> Seq(103, 104), //didn't move
      TopicAndPartition("topic2", 2) -> Seq(103, 104)  //didn't move
    )
    ReassignPartitionsCommand.executeAssignment(zkUtils, None,
      ReassignPartitionsCommand.formatAsReassignmentJson(newAssignment, Map.empty), Throttle(throttle))

    //Check throttle config. Should be throttling specific replicas for each topic.
    checkThrottleConfigAddedToZK(throttle, servers, "topic1",
      "1:100,1:101,0:100,0:101", //All replicas for moving partitions should be leader-throttled
      "1:102,0:102" //Move destinations should be follower throttled.
    )
    checkThrottleConfigAddedToZK(throttle, servers, "topic2",
      "1:104,1:105,0:104,0:105", //All replicas for moving partitions should be leader-throttled
      "1:103,0:103" //Move destinations should be follower throttled.
    )
  }

  @Test
  def shouldChangeThrottleOnRerunAndRemoveOnVerify() {
    //Given partitions on 3 of 3 brokers
    val brokers = Array(100, 101, 102)
    startBrokers(brokers)
    createTopic(zkUtils, topicName, Map(
      0 -> Seq(100, 101)
    ), servers = servers)

    //Given throttle set so replication will take at least 20 sec (we won't wait this long)
    val initialThrottle: Long = 1000 * 1000
    produceMessages(servers, topicName, numMessages = 200, acks = 0, valueBytes = 100 * 1000)

    //Start rebalance
    val newAssignment = generateAssignment(zkUtils, Array(101, 102), json(topicName), true)._1

    ReassignPartitionsCommand.executeAssignment(zkUtils, None,
      ReassignPartitionsCommand.formatAsReassignmentJson(newAssignment, Map.empty), Throttle(initialThrottle))

    //Check throttle config
    checkThrottleConfigAddedToZK(initialThrottle, servers, topicName, "0:100,0:101", "0:102")

    //Ensure that running Verify, whilst the command is executing, should have no effect
    verifyAssignment(zkUtils, None, ReassignPartitionsCommand.formatAsReassignmentJson(newAssignment, Map.empty))

    //Check throttle config again
    checkThrottleConfigAddedToZK(initialThrottle, servers, topicName, "0:100,0:101", "0:102")

    //Now re-run the same assignment with a larger throttle, which should only act to increase the throttle and make progress
    val newThrottle = initialThrottle * 1000

    ReassignPartitionsCommand.executeAssignment(zkUtils, None,
      ReassignPartitionsCommand.formatAsReassignmentJson(newAssignment, Map.empty), Throttle(newThrottle))

    //Check throttle was changed
    checkThrottleConfigAddedToZK(newThrottle, servers, topicName, "0:100,0:101", "0:102")

    //Await completion
    waitForReassignmentToComplete()

    //Verify should remove the throttle
    verifyAssignment(zkUtils, None, ReassignPartitionsCommand.formatAsReassignmentJson(newAssignment, Map.empty))

    //Check removed
    checkThrottleConfigRemovedFromZK(topicName, servers)

    //Check move occurred
    val actual = zkUtils.getPartitionAssignmentForTopics(Seq(topicName))(topicName)
    assertEquals(Seq(101, 102), actual.values.flatten.toSeq.distinct.sorted)
  }

  @Test(expected = classOf[AdminCommandFailedException])
  def shouldFailIfProposedDoesNotMatchExisting() {
    //Given a single replica on server 100
    startBrokers(Seq(100, 101))
    createTopic(zkUtils, topicName, Map(0 -> Seq(100)), servers = servers)

    //When we execute an assignment that includes an invalid partition (1:101 in this case)
    val topicJson = s"""{"version":1,"partitions":[{"topic":"$topicName","partition":1,"replicas":[101]}]}"""
    ReassignPartitionsCommand.executeAssignment(zkUtils, None, topicJson, NoThrottle)
  }

  @Test(expected = classOf[AdminCommandFailedException])
  def shouldFailIfProposedHasEmptyReplicaList() {
    //Given a single replica on server 100
    startBrokers(Seq(100, 101))
    createTopic(zkUtils, topicName, Map(0 -> Seq(100)), servers = servers)

    //When we execute an assignment that specifies an empty replica list (0: empty list in this case)
    val topicJson = s"""{"version":1,"partitions":[{"topic":"$topicName","partition":0,"replicas":[]}]}"""
    ReassignPartitionsCommand.executeAssignment(zkUtils, None, topicJson, NoThrottle)
  }

  @Test(expected = classOf[AdminCommandFailedException])
  def shouldFailIfProposedHasInvalidBrokerID() {
    //Given a single replica on server 100
    startBrokers(Seq(100, 101))
    createTopic(zkUtils, topicName, Map(0 -> Seq(100)), servers = servers)

    //When we execute an assignment that specifies an invalid brokerID (102: invalid broker ID in this case)
    val topicJson = s"""{"version":1,"partitions":[{"topic":"$topicName","partition":0,"replicas":[101, 102]}]}"""
    ReassignPartitionsCommand.executeAssignment(zkUtils, None, topicJson, NoThrottle)
  }

  @Test(expected = classOf[AdminCommandFailedException])
  def shouldFailIfProposedHasInvalidLogDir() {
    // Given a single replica on server 100
    startBrokers(Seq(100, 101))
    adminClient = createAdminClient(servers)
    createTopic(zkUtils, topicName, Map(0 -> Seq(100)), servers = servers)

    // When we execute an assignment that specifies an invalid log directory
    val topicJson: String = s"""{"version":1,"partitions":[{"topic":"$topicName","partition":0,"replicas":[101],"log_dirs":["invalidDir"]}]}"""
    ReassignPartitionsCommand.executeAssignment(zkUtils, Some(adminClient), topicJson, NoThrottle)
  }

  @Test(expected = classOf[AdminCommandFailedException])
  def shouldFailIfProposedHasInconsistentReplicasAndLogDirs() {
    // Given a single replica on server 100
    startBrokers(Seq(100, 101))
    adminClient = createAdminClient(servers)
    val logDir = getRandomLogDirAssignment(100)
    createTopic(zkUtils, topicName, Map(0 -> Seq(100)), servers = servers)

    // When we execute an assignment whose length of replicas doesn't match that of replicas
    val topicJson: String = s"""{"version":1,"partitions":[{"topic":"$topicName","partition":0,"replicas":[101],"log_dirs":["$logDir", "$logDir"]}]}"""
    ReassignPartitionsCommand.executeAssignment(zkUtils, Some(adminClient), topicJson, NoThrottle)
  }

  @Test
  def shouldPerformThrottledReassignmentOverVariousTopics() {
    val throttle = Throttle(1000L)

    startBrokers(Seq(0, 1, 2, 3))

    //With up several small topics
    createTopic(zkUtils, "orders", Map(0 -> List(0, 1, 2), 1 -> List(0, 1, 2)), servers)
    createTopic(zkUtils, "payments", Map(0 -> List(0, 1), 1 -> List(0, 1)), servers)
    createTopic(zkUtils, "deliveries", Map(0 -> List(0)), servers)
    createTopic(zkUtils, "customers", Map(0 -> List(0), 1 -> List(1), 2 -> List(2), 3 -> List(3)), servers)

    //Define a move for some of them
    val move = Map(
      TopicAndPartition("orders", 0) -> Seq(0, 2, 3),//moves
      TopicAndPartition("orders", 1) -> Seq(0, 1, 2),//stays
      TopicAndPartition("payments", 1) -> Seq(1, 2), //only define one partition as moving
      TopicAndPartition("deliveries", 0) -> Seq(1, 2) //increase replication factor
    )

    //When we run a throttled reassignment
    new ReassignPartitionsCommand(zkUtils, None, move).reassignPartitions(throttle)

    waitForReassignmentToComplete()

    //Check moved replicas did move
    assertEquals(Seq(0, 2, 3), zkUtils.getReplicasForPartition("orders", 0))
    assertEquals(Seq(0, 1, 2), zkUtils.getReplicasForPartition("orders", 1))
    assertEquals(Seq(1, 2), zkUtils.getReplicasForPartition("payments", 1))
    assertEquals(Seq(1, 2), zkUtils.getReplicasForPartition("deliveries", 0))

    //Check untouched replicas are still there
    assertEquals(Seq(0, 1), zkUtils.getReplicasForPartition("payments", 0))
    assertEquals(Seq(0), zkUtils.getReplicasForPartition("customers", 0))
    assertEquals(Seq(1), zkUtils.getReplicasForPartition("customers", 1))
    assertEquals(Seq(2), zkUtils.getReplicasForPartition("customers", 2))
    assertEquals(Seq(3), zkUtils.getReplicasForPartition("customers", 3))
  }

  /**
   * Verifies that the Controller sets a watcher for the reassignment znode after reassignment completion.
   * This includes the case where the znode is set immediately after it's deleted (i.e. before the watch is set).
   * This case relies on the scheduling of the operations, so it won't necessarily fail every time, but it fails
   * often enough to detect a regression.
   */
  @Test
  def shouldPerformMultipleReassignmentOperationsOverVariousTopics() {
    startBrokers(Seq(0, 1, 2, 3))

    createTopic(zkUtils, "orders", Map(0 -> List(0, 1, 2), 1 -> List(0, 1, 2)), servers)
    createTopic(zkUtils, "payments", Map(0 -> List(0, 1), 1 -> List(0, 1)), servers)
    createTopic(zkUtils, "deliveries", Map(0 -> List(0)), servers)
    createTopic(zkUtils, "customers", Map(0 -> List(0), 1 -> List(1), 2 -> List(2), 3 -> List(3)), servers)

    val firstMove = Map(
      TopicAndPartition("orders", 0) -> Seq(0, 2, 3), //moves
      TopicAndPartition("orders", 1) -> Seq(0, 1, 2), //stays
      TopicAndPartition("payments", 1) -> Seq(1, 2), //only define one partition as moving
      TopicAndPartition("deliveries", 0) -> Seq(1, 2) //increase replication factor
    )

    new ReassignPartitionsCommand(zkUtils, None, firstMove).reassignPartitions()
    // Low pause to detect deletion of the reassign_partitions znode before the reassignment is complete
    waitForReassignmentToComplete(pause = 1L)

    // Check moved replicas did move
    assertEquals(Seq(0, 2, 3), zkUtils.getReplicasForPartition("orders", 0))
    assertEquals(Seq(0, 1, 2), zkUtils.getReplicasForPartition("orders", 1))
    assertEquals(Seq(1, 2), zkUtils.getReplicasForPartition("payments", 1))
    assertEquals(Seq(1, 2), zkUtils.getReplicasForPartition("deliveries", 0))

    // Check untouched replicas are still there
    assertEquals(Seq(0, 1), zkUtils.getReplicasForPartition("payments", 0))
    assertEquals(Seq(0), zkUtils.getReplicasForPartition("customers", 0))
    assertEquals(Seq(1), zkUtils.getReplicasForPartition("customers", 1))
    assertEquals(Seq(2), zkUtils.getReplicasForPartition("customers", 2))
    assertEquals(Seq(3), zkUtils.getReplicasForPartition("customers", 3))

    // Define a move for some of them
    val secondMove = Map(
      TopicAndPartition("orders", 0) -> Seq(0, 2, 3), // stays
      TopicAndPartition("orders", 1) -> Seq(3, 1, 2), // moves
      TopicAndPartition("payments", 1) -> Seq(2, 1), // changed preferred leader
      TopicAndPartition("deliveries", 0) -> Seq(1, 2, 3) //increase replication factor
    )

    new ReassignPartitionsCommand(zkUtils, None, secondMove).reassignPartitions()
    // Low pause to detect deletion of the reassign_partitions znode before the reassignment is complete
    waitForReassignmentToComplete(pause = 1L)

    // Check moved replicas did move
    assertEquals(Seq(0, 2, 3), zkUtils.getReplicasForPartition("orders", 0))
    assertEquals(Seq(3, 1, 2), zkUtils.getReplicasForPartition("orders", 1))
    assertEquals(Seq(2, 1), zkUtils.getReplicasForPartition("payments", 1))
    assertEquals(Seq(1, 2, 3), zkUtils.getReplicasForPartition("deliveries", 0))

    //Check untouched replicas are still there
    assertEquals(Seq(0, 1), zkUtils.getReplicasForPartition("payments", 0))
    assertEquals(Seq(0), zkUtils.getReplicasForPartition("customers", 0))
    assertEquals(Seq(1), zkUtils.getReplicasForPartition("customers", 1))
    assertEquals(Seq(2), zkUtils.getReplicasForPartition("customers", 2))
    assertEquals(Seq(3), zkUtils.getReplicasForPartition("customers", 3))

    // We set the znode and then continuously attempt to set it again to exercise the case where the znode is set
    // immediately after deletion (i.e. before we set the watcher again)

    val thirdMove = Map(TopicAndPartition("orders", 0) -> Seq(1, 2, 3))

    new ReassignPartitionsCommand(zkUtils, None, thirdMove).reassignPartitions()

    val fourthMove = Map(TopicAndPartition("payments", 1) -> Seq(2, 3))

    // Continuously attempt to set the reassignment znode with `fourthMove` until it succeeds. It will only succeed
    // after `thirdMove` completes.
    Iterator.continually {
      try new ReassignPartitionsCommand(zkUtils, None, fourthMove).reassignPartitions()
      catch {
        case _: AdminCommandFailedException => false
      }
    }.exists(identity)

    // Low pause to detect deletion of the reassign_partitions znode before the reassignment is complete
    waitForReassignmentToComplete(pause = 1L)

    // Check moved replicas for thirdMove and fourthMove
    assertEquals(Seq(1, 2, 3), zkUtils.getReplicasForPartition("orders", 0))
    assertEquals(Seq(2, 3), zkUtils.getReplicasForPartition("payments", 1))

    //Check untouched replicas are still there
    assertEquals(Seq(3, 1, 2), zkUtils.getReplicasForPartition("orders", 1))
    assertEquals(Seq(1, 2, 3), zkUtils.getReplicasForPartition("deliveries", 0))
    assertEquals(Seq(0, 1), zkUtils.getReplicasForPartition("payments", 0))
    assertEquals(Seq(0), zkUtils.getReplicasForPartition("customers", 0))
    assertEquals(Seq(1), zkUtils.getReplicasForPartition("customers", 1))
    assertEquals(Seq(2), zkUtils.getReplicasForPartition("customers", 2))
    assertEquals(Seq(3), zkUtils.getReplicasForPartition("customers", 3))
  }

  /**
   * Set the `reassign_partitions` znode while the brokers are down and verify that the reassignment is triggered by
   * the Controller during start-up.
   */
  @Test
  def shouldTriggerReassignmentOnControllerStartup(): Unit = {
    startBrokers(Seq(0, 1, 2))
    createTopic(zkUtils, "orders", Map(0 -> List(0, 1), 1 -> List(1, 2)), servers)
    servers.foreach(_.shutdown())

    val firstMove = Map(
      new TopicPartition("orders", 0) -> Seq(2, 1), // moves
      new TopicPartition("orders", 1) -> Seq(1, 2), // stays
      new TopicPartition("customers", 0) -> Seq(1, 2) // non-existent topic, triggers topic deleted path
    )

    // Set znode directly to avoid non-existent topic validation
    zkClient.setOrCreatePartitionReassignment(firstMove)

    servers.foreach(_.startup())
    waitForReassignmentToComplete()

    assertEquals(Seq(2, 1), zkUtils.getReplicasForPartition("orders", 0))
    assertEquals(Seq(1, 2), zkUtils.getReplicasForPartition("orders", 1))
    assertEquals(Seq.empty, zkUtils.getReplicasForPartition("customers", 0))
  }

  def waitForReassignmentToComplete(pause: Long = 100L) {
    waitUntilTrue(() => !zkUtils.pathExists(ReassignPartitionsPath),
      s"Znode ${ZkUtils.ReassignPartitionsPath} wasn't deleted", pause = pause)
  }

  def json(topic: String*): String = {
    val topicStr = topic.map { t => "{\"topic\": \"" + t + "\"}" }.mkString(",")
    s"""{"topics": [$topicStr],"version":1}"""
  }
}
