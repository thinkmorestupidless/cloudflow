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

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import cloudflow.crd.ResetOffsets
import cloudflow.pekkostream.testdata.Data
import cloudflow.streamlets.Topic
import cloudflow.streamlets.avro.AvroInlet

/** The operator resets the consumer group it computes; the runtime reads with the one it computes. If the two ever
  * disagreed, a reset would succeed on a group nothing reads, and change nothing — silently.
  */
class ConsumerGroupNamingSpec extends AnyWordSpec with Matchers {
  "The consumer group the operator resets" should {
    "be the one the runtime reads an inlet with" in {
      val inlet = AvroInlet[Data]("in")
      Topic("events", ConfigFactory.empty()).groupId("shop", "processor", inlet) mustBe
      ResetOffsets.groupId("shop", "processor", "in")
    }
  }
}
