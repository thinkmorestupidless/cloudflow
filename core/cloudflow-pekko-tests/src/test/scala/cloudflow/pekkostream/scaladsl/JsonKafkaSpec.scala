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
import java.util.{ Properties, UUID }

import scala.concurrent.duration._

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl._
import org.apache.pekko.testkit.TestProbe

import org.apache.kafka.clients.producer.{ KafkaProducer, ProducerConfig, ProducerRecord }
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArraySerializer

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.typesafe.config._

import cloudflow.pekkostream._
import cloudflow.pekkostream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.json._

import org.scalatest.time._

final case class CartItemAdded(cartId: String, productId: String, quantity: Int)
object CartItemAdded {
  implicit val codec: JsonValueCodec[CartItemAdded] = JsonCodecMaker.make
}

object JsonKafkaSpec {
  val config = ConfigFactory.parseString("""
      pekko {
        stdout-loglevel = "OFF"
        loglevel = "OFF"
      }
      cloudflow.pekko.consumer-stop-timeout = 5 s
      """)
}

import JsonKafkaSpec._

/** A topic written the way a nakka service writes one — a plain Kafka producer, CloudEvents in binary mode: the subject
  * as the record key, attributes as `ce_*` headers, the event as a JSON body — read by a Cloudflow streamlet through a
  * JSON inlet.
  */
class JsonKafkaSpec extends TestcontainersKafkaSpec(ActorSystem("json-kafka", config)) {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(30, Seconds), interval = Span(50, Millis))

  "A streamlet with a JSON inlet" should {
    "read events another service published, with their CloudEvents key and headers, skipping one that does not decode" in {
      val topic = s"cart-events-${UUID.randomUUID()}"
      val events =
        List(CartItemAdded("cart-1", "p1", 1), CartItemAdded("cart-2", "p2", 2), CartItemAdded("cart-1", "p3", 3))

      publish(topic, events.take(2).map(e => cloudEvent(topic, e, writeToArray(e))))
      publish(
        topic,
        List(cloudEvent(topic, CartItemAdded("cart-9", "bad", 0), """{"cartId":"cart-9",""".getBytes(UTF_8))))
      publish(topic, events.drop(2).map(e => cloudEvent(topic, e, writeToArray(e))))

      val probe = TestProbe()
      val execution = run(new Reader(Sink.actorRef(probe.ref, "done", _ => "failed")), topic)

      val received = probe.receiveN(events.size, 30.seconds).map(_.asInstanceOf[Record[CartItemAdded]])
      probe.expectNoMessage(2.seconds)
      execution.stop().futureValue

      // Kafka orders within a partition only, so across keys the order is free; within one key it is not.
      received.map(_.value) must contain theSameElementsAs events
      received.filter(_.key.contains("cart-1")).map(_.value.productId) mustBe List("p1", "p3")
      received.foreach { record =>
        record.key mustBe Some(record.value.cartId)
        record.headerValue("ce_subject") mustBe Some(record.value.cartId)
        record.headerValue("ce_type") mustBe Some("nakka.cart.ItemAdded")
        record.headerValue("ce_specversion") mustBe Some("1.0")
      }
      received.map(_.headerValue("ce_id")).distinct must have size events.size.toLong
    }
  }

  class Reader(sink: Sink[Record[CartItemAdded], _]) extends PekkoStreamlet {
    val in = JsonInlet[CartItemAdded]("in").withSchemaName("nakka.cart-events.v1")
    final override val shape = StreamletShape.withInlets(in)
    override final def createLogic = new RunnableGraphStreamletLogic() {
      def runnableGraph = plainRecordSource(in, Earliest).to(sink)
    }
  }

  private def cloudEvent(
      topic: String,
      event: CartItemAdded,
      body: Array[Byte]): ProducerRecord[Array[Byte], Array[Byte]] = {
    val record = new ProducerRecord[Array[Byte], Array[Byte]](topic, event.cartId.getBytes(UTF_8), body)
    List(
      "ce_specversion" -> "1.0",
      "ce_id" -> UUID.randomUUID().toString,
      "ce_source" -> "/services/cart",
      "ce_type" -> "nakka.cart.ItemAdded",
      "ce_subject" -> event.cartId,
      "content-type" -> "application/json").foreach { case (k, v) =>
      record.headers.add(new RecordHeader(k, v.getBytes(UTF_8)))
    }
    record
  }

  private def publish(topic: String, records: List[ProducerRecord[Array[Byte], Array[Byte]]]): Unit = {
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, s"localhost:$kafkaPort")
    val producer = new KafkaProducer(props, new ByteArraySerializer, new ByteArraySerializer)
    try records.foreach(producer.send(_).get())
    finally producer.close()
  }

  private def run(streamlet: PekkoStreamlet, topic: String): StreamletExecution = {
    val definition = StreamletDefinition(
      appId = "json-app",
      appVersion = "1",
      streamletRef = "reader",
      streamletClass = streamlet.getClass.getName,
      portMappings = List(
        PortMapping("in", Topic(topic, ConfigFactory.parseString(s"""bootstrap.servers = "localhost:$kafkaPort"""")))),
      volumeMounts = Nil,
      config = config)
    val context = new PekkoStreamletContextImpl(definition, system)
    streamlet.setContext(context)
    streamlet.run(context)
  }
}
