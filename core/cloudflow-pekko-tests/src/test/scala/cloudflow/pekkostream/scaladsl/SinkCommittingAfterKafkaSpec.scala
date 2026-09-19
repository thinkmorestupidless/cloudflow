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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

import org.apache.pekko.actor.ActorSystem

import org.apache.kafka.clients.admin.{ Admin, AdminClientConfig }
import org.apache.kafka.clients.producer.{ KafkaProducer, ProducerConfig, ProducerRecord }
import org.apache.kafka.common.serialization.ByteArraySerializer

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.typesafe.config._

import cloudflow.pekkostream._
import cloudflow.pekkostream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.json._

import org.scalatest.concurrent.Eventually
import org.scalatest.time._

final case class Item(n: Int)
object Item {
  implicit val codec: JsonValueCodec[Item] = JsonCodecMaker.make
}

object SinkCommittingAfterKafkaSpec {
  val config = ConfigFactory.parseString("""
      pekko {
        stdout-loglevel = "OFF"
        loglevel = "OFF"
      }
      cloudflow.pekko.consumer-stop-timeout = 1 s
      """)
  val Records = 20
  val FailingRecord = 12
}

import SinkCommittingAfterKafkaSpec._

/** What the commit-after-write sink commits, against real Kafka: never an offset whose record has not been written, and
  * after a failed write, a restart resumes from the last commit so that every record is written — none skipped.
  */
class SinkCommittingAfterKafkaSpec
    extends TestcontainersKafkaSpec(ActorSystem("sink-committing-after", config))
    with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(30, Seconds), interval = Span(100, Millis))

  "A sink committing after its write" should {
    "commit only what it has written, and after a failed write resume from there without skipping a record" in {
      val topic = s"items-${UUID.randomUUID()}"
      publish(topic)
      val written = new ConcurrentLinkedQueue[Int]()

      // First run: the write fails on the batch holding record 12, which stops the stream.
      val failOn = new AtomicInteger(FailingRecord)
      val first = run(new Writer(written, failOn), topic)
      eventually(failOn.get mustBe -1)
      Try(first.stop().futureValue)

      val committedAfterFailure = committed()
      withClue(s"committed $committedAfterFailure, written ${written.asScala.toList.sorted}: ") {
        committedAfterFailure must be <= FailingRecord.toLong
        (0L until committedAfterFailure).forall(n => written.contains(n.toInt)) mustBe true
        written.contains(FailingRecord) mustBe false
      }

      // Second run, same consumer group: it resumes from the last commit and writes the rest.
      val second = run(new Writer(written, new AtomicInteger(-1)), topic)
      eventually(written.asScala.toSet mustBe (0 until Records).toSet)
      eventually(committed() mustBe Records.toLong)
      second.stop().futureValue
    }
  }

  /** Writes a batch, unless it holds the record `failOn` names: then it fails, once. */
  final class Writer(written: ConcurrentLinkedQueue[Int], failOn: AtomicInteger) extends PekkoStreamlet {
    val in = JsonInlet[Item]("in")
    final override val shape = StreamletShape.withInlets(in)

    private def write(batch: immutable.Seq[Item]): Future[Unit] =
      if (batch.exists(_.n == failOn.get)) {
        failOn.set(-1)
        Future.failed(new RuntimeException(s"write failed on ${batch.map(_.n)}"))
      } else {
        batch.foreach(item => written.add(item.n))
        Future.unit
      }

    override final def createLogic = new RunnableGraphStreamletLogic() {
      def runnableGraph =
        sourceWithCommittableContext(in).to(sinkCommittingAfter(write, batchSize = 5, batchWithin = 200.millis))
    }
  }

  /** All records under one key, so on one partition, where each record's offset is its number. */
  private def publish(topic: String): Unit = {
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, s"localhost:$kafkaPort")
    val producer = new KafkaProducer(props, new ByteArraySerializer, new ByteArraySerializer)
    try
      (0 until Records).foreach { n =>
        producer.send(new ProducerRecord(topic, "one-key".getBytes(UTF_8), writeToArray(Item(n)))).get()
      }
    finally producer.close()
  }

  private def committed(): Long = {
    val props = new Properties()
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, s"localhost:$kafkaPort")
    val admin = Admin.create(props)
    try
      admin
        .listConsumerGroupOffsets("items-app.writer.in")
        .partitionsToOffsetAndMetadata()
        .get()
        .asScala
        .values
        .map(_.offset)
        .sum
    finally admin.close()
  }

  private def run(streamlet: PekkoStreamlet, topic: String): StreamletExecution = {
    val definition = StreamletDefinition(
      appId = "items-app",
      appVersion = "1",
      streamletRef = "writer",
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
