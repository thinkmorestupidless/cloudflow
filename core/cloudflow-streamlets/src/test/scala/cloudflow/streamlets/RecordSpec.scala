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

package cloudflow.streamlets

import java.nio.charset.StandardCharsets.UTF_8

import scala.jdk.CollectionConverters._

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RecordSpec extends AnyWordSpec with Matchers {

  "A Header" should {
    "equal another with the same key and the same bytes, whatever the arrays' identity" in {
      Header("ce_type", "created".getBytes(UTF_8)) mustBe Header("ce_type", "created".getBytes(UTF_8))
      Header("ce_type", "created".getBytes(UTF_8)).hashCode mustBe Header("ce_type", "created").hashCode
      Header("ce_type", "created") must not be Header("ce_type", "deleted")
      Header("ce_type", "created") must not be Header("ce_id", "created")
    }

    "read its value as UTF-8" in {
      Header("ce_subject", "cart-€1").valueAsString mustBe "cart-€1"
    }
  }

  "A Record" should {
    "return the last header with a repeated key, as Kafka's lastHeader does" in {
      val record = Record("v").withHeader("h", "first").withHeader("other", "x").withHeader("h", "second")
      record.headerValue("h") mustBe Some("second")
      record.headerValue("missing") mustBe None
    }

    "keep its key and headers, in order, through a change of value" in {
      val record = Record(1, Some("k"), List(Header("a", "1"), Header("b", "2")))
      val mapped = record.map(_.toString)
      mapped mustBe Record("1", Some("k"), List(Header("a", "1"), Header("b", "2")))
      record.withValue("x").headers mustBe record.headers
    }

    "treat a null key as no key" in {
      Record("v").withKey(null).key mustBe None
      Record.create[String](null, "v").key mustBe None
    }

    "offer the same through its Java API" in {
      val record = Record.create("k", "v", List(Header.create("a", "1")).asJava)
      record.getKey().get mustBe "k"
      record.getValue() mustBe "v"
      record.getHeaders().asScala mustBe List(Header("a", "1"))
      record.getHeaderValue("a").get mustBe "1"
      Record.create("v").getKey().isPresent mustBe false
    }
  }
}
