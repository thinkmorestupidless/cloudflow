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

package cloudflow.sbt

import scala.io.Source
import scala.util.matching.Regex

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The rules the Prometheus JMX agent runs with inside every streamlet image, against the JMX names a streamlet's Kafka
  * client actually registers — `<appId>.<streamletRef>.<port>` as its client id (`PekkoStreamletContextImpl.clientId`,
  * pinned by `ConsumerLagKafkaSpec`). A rule that does not match exports no metric, silently.
  */
class PrometheusRulesSpec extends AnyWordSpec with Matchers {

  private val ClientId = "shop.processor.in"
  private val Topic = "nakka.cart-events.v1"

  /** As the JMX exporter builds a name from an MBean: `domain<properties><>attribute`. */
  private def jmxName(domain: String, properties: (String, String)*)(attribute: String) =
    s"$domain<${properties.map { case (k, v) => s"$k=$v" }.mkString(", ")}><>$attribute"

  private val patterns: List[Regex] = {
    val yaml = Source.fromResource("runtimes/pekko/prometheus.yaml").getLines().toList
    val found = yaml.collect {
      case line if line.trim.startsWith("- pattern:") => line.trim.stripPrefix("- pattern:").trim.r
    }
    found must not be empty
    found
  }

  private def firstMatch(name: String): Option[Regex] = patterns.find(_.findFirstIn(name).isDefined)

  "The Prometheus rules shipped in the streamlet image" should {
    "export a consumer's per-partition lag, labelled with the streamlet's client id, topic and partition" in {
      val name = jmxName(
        "kafka.consumer",
        "type" -> "consumer-fetch-manager-metrics",
        "client-id" -> ClientId,
        "topic" -> Topic,
        "partition" -> "0")("records-lag")

      val rule = firstMatch(name).value
      val groups = rule.findFirstMatchIn(name).value
      groups.group(1) mustBe ClientId
      groups.group(2) mustBe Topic
      groups.group(3) mustBe "0"
    }

    "export records-lag-max by its own rule, not the records-lag one, which its name also matches" in {
      val maxName = jmxName(
        "kafka.consumer",
        "type" -> "consumer-fetch-manager-metrics",
        "client-id" -> ClientId,
        "topic" -> Topic,
        "partition" -> "0")("records-lag-max")
      val lagName = jmxName(
        "kafka.consumer",
        "type" -> "consumer-fetch-manager-metrics",
        "client-id" -> ClientId,
        "topic" -> Topic,
        "partition" -> "0")("records-lag")

      // The exporter applies the first rule that matches, and `records-lag` is a prefix of `records-lag-max`:
      // the order in the file is what keeps them apart.
      firstMatch(maxName).value.regex must include("records-lag-max")
      (firstMatch(lagName).value.regex must not).include("records-lag-max")
    }

    "export a producer's send rate under the same client id scheme" in {
      val name = jmxName(
        "kafka.producer",
        "type" -> "producer-topic-metrics",
        "client-id" -> "shop.processor.out",
        "topic" -> Topic)("record-send-rate")
      firstMatch(name).value.findFirstMatchIn(name).value.group(1) mustBe "shop.processor.out"
    }
  }

  implicit private class OptionOps[T](option: Option[T]) {
    def value: T = option.getOrElse(fail("no match"))
  }
}
