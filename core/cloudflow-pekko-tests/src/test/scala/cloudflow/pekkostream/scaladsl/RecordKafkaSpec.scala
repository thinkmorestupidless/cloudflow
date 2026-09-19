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

package cloudflow.pekkostream.util.scaladsl

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.{ Properties, UUID }

import scala.collection.mutable
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl._

import org.apache.kafka.clients.consumer.{ ConsumerConfig, ConsumerRecord, KafkaConsumer }
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import com.typesafe.config._
import cloudflow.pekkostream._
import cloudflow.pekkostream.scaladsl._
import cloudflow.pekkostream.testdata._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

import org.scalatest.time._

object RecordKafkaSpec {
  val config = ConfigFactory.parseString("""
      pekko {
        stdout-loglevel = "OFF"
        loglevel = "OFF"
      }
      cloudflow.pekko.consumer-stop-timeout = 5 s
      """)

  val Keys = 10
  val PerKey = 5
  val Binary: Array[Byte] = Array[Byte](0, -1, 42, 127, -128)
}

import RecordKafkaSpec._

/** Keys and headers across real Kafka: written by a record sink, read by a record source, written on again — and what
  * reaches the wire checked with a plain Kafka consumer, so nothing here trusts Cloudflow to report on itself.
  */
class RecordKafkaSpec extends TestcontainersKafkaSpec(ActorSystem("record-kafka", config)) {
  import system.dispatcher

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(30, Seconds), interval = Span(50, Millis))

  "A streamlet reading and writing records" should {
    "carry every key and header across Kafka unchanged, keep each key on one partition, and keep its order" in {
      val genTopic = s"gen-${UUID.randomUUID()}"
      val relayTopic = s"relay-${UUID.randomUUID()}"

      val generated = for {
        k <- 0 until Keys
        n <- 0 until PerKey
      } yield {
        val id = k * PerKey + n
        Record(
          Data(id, s"n$id"),
          Some(s"cart-$k"),
          List(Header("ce_type", "ItemAdded"), Header("ce_id", id.toString), new Header("raw", Binary)))
      }

      run(new Generator(generated.toList), "gen", ports("out" -> genTopic)).completed.futureValue
      val relay = run(new Relay, "relay", ports("in" -> genTopic, "out" -> relayTopic))

      val received = consumeAll(relayTopic, generated.size)
      relay.stop().futureValue

      received must have size generated.size.toLong
      val codec = AvroOutlet[Data]("x").codec
      val byId = received.map(r => codec.decode(r.value).get.id -> r).toMap

      generated.foreach { expected =>
        val actual = byId(expected.value.id)
        new String(actual.key, UTF_8) mustBe expected.key.get
        actual.headers.toArray.map(h => h.key).toList mustBe List("ce_type", "ce_id", "raw", "stage")
        new String(actual.headers.lastHeader("ce_type").value, UTF_8) mustBe "ItemAdded"
        new String(actual.headers.lastHeader("ce_id").value, UTF_8) mustBe expected.value.id.toString
        actual.headers.lastHeader("raw").value.toList mustBe Binary.toList
        new String(actual.headers.lastHeader("stage").value, UTF_8) mustBe "relay"
      }

      val byKey = received.groupBy(r => new String(r.key, UTF_8))
      byKey.foreach { case (key, records) =>
        withClue(s"$key: ") {
          records.map(_.partition).distinct must have size 1
          records.sortBy(_.offset).map(r => codec.decode(r.value).get.id) mustBe records
            .map(r => codec.decode(r.value).get.id)
            .sorted
        }
      }
      // The keys spread: nothing collapsed everything onto one partition (the topic has 53).
      byKey.values.map(_.head.partition).toSet.size must be > 1
    }
  }

  class Generator(records: List[Record[Data]]) extends PekkoStreamlet {
    val out = AvroOutlet[Data]("out")
    final override val shape = StreamletShape.withOutlets(out)
    override final def createLogic = new RunnableGraphStreamletLogic() {
      def runnableGraph = Source(records).to(plainRecordSink(out))
    }
  }

  class Relay extends PekkoStreamlet {
    val in = AvroInlet[Data]("in")
    val out = AvroOutlet[Data]("out")
    final override val shape = StreamletShape.withInlets(in).withOutlets(out)
    override final def createLogic = new RunnableGraphStreamletLogic() {
      def runnableGraph =
        recordSourceWithCommittableContext(in)
          .map(_.withHeader("stage", "relay"))
          .to(committableRecordSink(out))
    }
  }

  private def ports(mappings: (String, String)*): List[PortMapping] =
    mappings.toList.map { case (port, topic) =>
      PortMapping(port, Topic(topic, ConfigFactory.parseString(s"""bootstrap.servers = "localhost:$kafkaPort"""")))
    }

  private def run(streamlet: PekkoStreamlet, ref: String, portMappings: List[PortMapping]): StreamletExecution = {
    val definition = StreamletDefinition(
      appId = "record-app",
      appVersion = "1",
      streamletRef = ref,
      streamletClass = streamlet.getClass.getName,
      portMappings = portMappings,
      volumeMounts = Nil,
      config = config)
    val context = new PekkoStreamletContextImpl(definition, system)
    streamlet.setContext(context)
    streamlet.run(context)
  }

  private def consumeAll(topic: String, count: Int): List[ConsumerRecord[Array[Byte], Array[Byte]]] = {
    val props = new Properties()
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, s"localhost:$kafkaPort")
    props.put(ConsumerConfig.GROUP_ID_CONFIG, s"verify-${UUID.randomUUID()}")
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    val consumer = new KafkaConsumer(props, new ByteArrayDeserializer, new ByteArrayDeserializer)
    try {
      consumer.subscribe(List(topic).asJava)
      val records = mutable.ListBuffer.empty[ConsumerRecord[Array[Byte], Array[Byte]]]
      val deadline = 30.seconds.fromNow
      while (records.size < count && deadline.hasTimeLeft())
        records ++= consumer.poll(Duration.ofMillis(200)).asScala
      records.toList
    } finally consumer.close()
  }
}
