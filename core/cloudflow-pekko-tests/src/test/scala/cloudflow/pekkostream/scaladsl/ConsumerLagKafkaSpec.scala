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

import java.lang.management.ManagementFactory
import java.util.UUID
import javax.management.ObjectName

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl._

import com.typesafe.config._

import cloudflow.pekkostream._
import cloudflow.pekkostream.scaladsl._
import cloudflow.pekkostream.testdata._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

import org.scalatest.concurrent.Eventually
import org.scalatest.time._

object ConsumerLagKafkaSpec {
  val config = ConfigFactory.parseString("""
      pekko {
        stdout-loglevel = "OFF"
        loglevel = "OFF"
      }
      cloudflow.pekko.consumer-stop-timeout = 1 s
      """)
  val Records = 50
}

import ConsumerLagKafkaSpec._

/** A streamlet's Kafka clients register their metrics under a client id naming the streamlet and its port, so that a
  * consumer's lag — how far behind a pipeline is — can be told apart per streamlet. The Prometheus agent in the
  * streamlet image scrapes these very MBeans; `PrometheusRulesSpec` checks its rules against these names.
  */
class ConsumerLagKafkaSpec extends TestcontainersKafkaSpec(ActorSystem("consumer-lag", config)) with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(30, Seconds), interval = Span(200, Millis))

  private val mbeans = ManagementFactory.getPlatformMBeanServer

  "A streamlet's Kafka clients" should {
    "report their metrics under <appId>.<streamletRef>.<port>, the consumer's lag included" in {
      val topic = s"lag-${UUID.randomUUID()}"

      val generator = run(new Generator, "gen", "out" -> topic)
      generator.completed.futureValue

      eventually {
        producerMetrics("lag-app.gen.out", topic) must not be empty
      }

      val reader = run(new Reader, "reader", "in" -> topic)
      eventually {
        val lags = consumerLagMetrics("lag-app.reader.in", topic)
        lags must not be empty
        // Caught up: the reader has read everything the generator wrote.
        lags.values.max mustBe 0.0
      }
      reader.stop().futureValue
    }
  }

  class Generator extends PekkoStreamlet {
    val out = AvroOutlet[Data]("out")
    final override val shape = StreamletShape.withOutlets(out)
    override final def createLogic = new RunnableGraphStreamletLogic() {
      def runnableGraph = Source((0 until Records).map(i => Data(i, s"n$i"))).to(plainSink(out))
    }
  }

  class Reader extends PekkoStreamlet {
    val in = AvroInlet[Data]("in")
    final override val shape = StreamletShape.withInlets(in)
    override final def createLogic = new RunnableGraphStreamletLogic() {
      def runnableGraph = plainSource(in, Earliest).to(Sink.ignore)
    }
  }

  /** `records-lag` per partition, as the Prometheus agent reads it out of JMX. */
  private def consumerLagMetrics(clientId: String, topic: String): Map[ObjectName, Double] =
    metrics("kafka.consumer", s"type=consumer-fetch-manager-metrics,client-id=$clientId,topic=$topic,partition=*")(
      "records-lag")

  private def producerMetrics(clientId: String, topic: String): Map[ObjectName, Double] =
    metrics("kafka.producer", s"type=producer-topic-metrics,client-id=$clientId,topic=$topic")("record-send-total")

  private def metrics(domain: String, properties: String)(attribute: String): Map[ObjectName, Double] =
    mbeans
      .queryNames(new ObjectName(s"$domain:$properties"), null)
      .asScala
      .toList
      .map(name => name -> mbeans.getAttribute(name, attribute).asInstanceOf[Double])
      .toMap

  private def run(streamlet: PekkoStreamlet, ref: String, port: (String, String)): StreamletExecution = {
    val (portName, topic) = port
    val definition = StreamletDefinition(
      appId = "lag-app",
      appVersion = "1",
      streamletRef = ref,
      streamletClass = streamlet.getClass.getName,
      portMappings = List(
        PortMapping(
          portName,
          Topic(topic, ConfigFactory.parseString(s"""bootstrap.servers = "localhost:$kafkaPort"""")))),
      volumeMounts = Nil,
      config = config)
    val context = new PekkoStreamletContextImpl(definition, system)
    streamlet.setContext(context)
    streamlet.run(context)
  }
}
