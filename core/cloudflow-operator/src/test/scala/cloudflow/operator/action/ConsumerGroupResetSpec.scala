/*
 * Copyright (C) 2016-2026 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.operator.action

import java.time.Duration
import java.util.{ Properties, UUID }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.jdk.CollectionConverters._

import org.apache.kafka.clients.admin.{ Admin, AdminClientConfig, NewTopic }
import org.apache.kafka.clients.consumer.{ ConsumerConfig, KafkaConsumer }
import org.apache.kafka.clients.producer.{ KafkaProducer, ProducerConfig, ProducerRecord }
import org.apache.kafka.common.serialization.{ StringDeserializer, StringSerializer }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName

/** The reset against a real broker: committed offsets move back, and a group in use is refused. */
class ConsumerGroupResetSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:5.4.3"))
  private lazy val admin = Admin.create(props(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> kafka.getBootstrapServers))

  override def beforeAll(): Unit = kafka.start()
  override def afterAll(): Unit = {
    admin.close()
    kafka.stop()
  }

  private val Partitions = 3
  private val Records = 30

  "Resetting a consumer group to earliest" should {
    "move every partition's committed offset back to the start of the topic" in {
      val topic = newTopicWithRecords()
      val group = s"app.streamlet.in-${UUID.randomUUID()}"
      consumeEverythingAndCommit(topic, group)
      committed(group).values.sum mustBe Records

      await(ConsumerGroupReset.toEarliest(admin, group, topic)) mustBe Partitions

      committed(group) mustBe (0 until Partitions).map(_ -> 0L).toMap
    }

    "refuse a group that still has an active member, and leave its offsets alone" in {
      val topic = newTopicWithRecords()
      val group = s"app.streamlet.in-${UUID.randomUUID()}"
      val consumer = subscribedConsumer(topic, group)
      try {
        readAll(consumer)
        consumer.commitSync()
        val failure = the[ConsumerGroupReset.GroupHasActiveMembers] thrownBy
          await(ConsumerGroupReset.toEarliest(admin, group, topic))
        failure.groupId mustBe group
        failure.getMessage must include("scale its streamlet to 0")
        committed(group).values.sum mustBe Records
      } finally consumer.close()
    }

    "give a group that never read the topic offsets at its start" in {
      val topic = newTopicWithRecords()
      val group = s"never-${UUID.randomUUID()}"
      await(ConsumerGroupReset.toEarliest(admin, group, topic)) mustBe Partitions
      committed(group) mustBe (0 until Partitions).map(_ -> 0L).toMap
    }
  }

  private def newTopicWithRecords(): String = {
    val topic = s"events-${UUID.randomUUID()}"
    admin.createTopics(List(new NewTopic(topic, Partitions, 1.toShort)).asJava).all().get()
    val producer = new KafkaProducer[String, String](
      props(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafka.getBootstrapServers),
      new StringSerializer,
      new StringSerializer)
    try (0 until Records).foreach(i => producer.send(new ProducerRecord(topic, s"key-$i", s"value-$i")).get())
    finally producer.close()
    topic
  }

  private def subscribedConsumer(topic: String, group: String): KafkaConsumer[String, String] = {
    val consumer = new KafkaConsumer[String, String](
      props(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafka.getBootstrapServers,
        ConsumerConfig.GROUP_ID_CONFIG -> group,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest",
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false"),
      new StringDeserializer,
      new StringDeserializer)
    consumer.subscribe(List(topic).asJava)
    consumer
  }

  private def readAll(consumer: KafkaConsumer[String, String]): Unit = {
    var read = 0
    val deadline = 30.seconds.fromNow
    while (read < Records && deadline.hasTimeLeft()) read += consumer.poll(Duration.ofMillis(200)).count()
    read mustBe Records
  }

  private def consumeEverythingAndCommit(topic: String, group: String): Unit = {
    val consumer = subscribedConsumer(topic, group)
    try {
      readAll(consumer)
      consumer.commitSync()
    } finally consumer.close()
  }

  private def committed(group: String): Map[Int, Long] =
    admin
      .listConsumerGroupOffsets(group)
      .partitionsToOffsetAndMetadata()
      .get()
      .asScala
      .map { case (partition, offset) => partition.partition -> offset.offset }
      .toMap

  private def await[T](future: scala.concurrent.Future[T]): T = Await.result(future, 30.seconds)

  private def props(entries: (String, String)*): Properties = {
    val p = new Properties()
    entries.foreach { case (k, v) => p.put(k, v) }
    p
  }
}
