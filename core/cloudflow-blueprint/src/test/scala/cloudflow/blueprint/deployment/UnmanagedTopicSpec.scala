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

package cloudflow.blueprint.deployment

import org.scalatest._
import org.scalatest.matchers.must._
import org.scalatest.wordspec._

import cloudflow.blueprint._
import cloudflow.blueprint.BlueprintBuilder._

/** A blueprint consuming a topic another system owns — a nakka service's event topic — declared the way a user writes
  * it, followed from the blueprint to the port mapping the consuming streamlet runs with.
  */
class UnmanagedTopicSpec extends AnyWordSpec with Matchers with OptionValues {

  private val agentPaths = Map(ApplicationDescriptor.PrometheusAgentKey -> "/app/prometheus/prometheus.jar")

  private val cartEvents = SchemaDescriptor("nakka.cart-events.v1", "nakka.cart-events.v1", "fp", "json")
  private val graphSink = streamlet("sample.GraphSink").asBox.copy(inlets = Vector(InletDescriptor("in", cartEvents)))

  private val blueprintConf =
    """blueprint {
      |  streamlets {
      |    graph = sample.GraphSink
      |  }
      |  topics {
      |    cart-events {
      |      topic.name = "nakka.cart-events.v1"
      |      managed = false
      |      bootstrap.servers = "nakka-kafka-bootstrap.kafka.svc:9092"
      |      consumer-config {
      |        auto.offset.reset = earliest
      |      }
      |      consumers = [graph.in]
      |    }
      |  }
      |}
      |""".stripMargin

  private lazy val blueprint = Blueprint.parseString(blueprintConf, Vector(graphSink)).verify

  "A blueprint consuming a topic Cloudflow does not own" should {
    "verify with consumers only: nothing in the blueprint produces to it" in {
      blueprint.problems mustBe empty
    }

    "hand the consuming streamlet the external topic's name, its brokers and its consumer settings, marked unmanaged" in {
      val descriptor =
        ApplicationDescriptor("graph-app", "1", "image", blueprint.verified.value, agentPaths, BuildInfo.version)
      val topic = descriptor.deployments.find(_.streamletName == "graph").value.portMappings("in")

      topic.id mustBe "cart-events"
      topic.name mustBe "nakka.cart-events.v1"
      topic.managed mustBe false
      topic.config.getString("bootstrap.servers") mustBe "nakka-kafka-bootstrap.kafka.svc:9092"
      cloudflow.blueprint.deployment.Topic.pathAsMap(topic.config, "consumer-config") mustBe Map(
        "auto.offset.reset" -> "earliest")
    }
  }
}
