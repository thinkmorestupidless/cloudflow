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

package cloudflow.streamlets.json

import java.nio.charset.StandardCharsets.UTF_8

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

final case class ItemAdded(cartId: String, productId: String, quantity: Int)
object ItemAdded {
  implicit val codec: JsonValueCodec[ItemAdded] = JsonCodecMaker.make
}

class JsonSpec extends AnyWordSpec with Matchers {

  "A JSON inlet and outlet" should {
    "round-trip a value through the codec" in {
      val event = ItemAdded("c1", "p1", 2)
      val bytes = JsonOutlet[ItemAdded]("out").codec.encode(event)
      new String(bytes, UTF_8) mustBe """{"cartId":"c1","productId":"p1","quantity":2}"""
      JsonInlet[ItemAdded]("in").codec.decode(bytes).get mustBe event
    }

    "decode JSON another service wrote, field order and all" in {
      val bytes = """{ "quantity": 3, "productId": "p9", "cartId": "c9" }""".getBytes(UTF_8)
      JsonInlet[ItemAdded]("in").codec.decode(bytes).get mustBe ItemAdded("c9", "p9", 3)
    }

    "fail to decode, not throw, on bytes that are not the expected JSON" in {
      JsonInlet[ItemAdded]("in").codec.decode("not json".getBytes(UTF_8)).isFailure mustBe true
      JsonInlet[ItemAdded]("in").codec.decode("""{"cartId":"c1"}""".getBytes(UTF_8)).isFailure mustBe true
    }

    "name the schema after the type unless told otherwise" in {
      JsonInlet[ItemAdded]("in").schemaDefinition.name mustBe classOf[ItemAdded].getName
      JsonInlet[ItemAdded]("in").withSchemaName("nakka.cart-events.v1").schemaDefinition.name mustBe
      "nakka.cart-events.v1"
    }

    "fingerprint by schema name alone, so equal names connect and different names do not" in {
      val inlet = JsonInlet[ItemAdded]("in").withSchemaName("nakka.cart-events.v1")
      val outlet = JsonOutlet[ItemAdded]("out").withSchemaName("nakka.cart-events.v1")
      inlet.schemaDefinition.format mustBe "json"
      inlet.schemaDefinition.fingerprint mustBe outlet.schemaDefinition.fingerprint
      inlet.schemaDefinition.fingerprint must not be outlet
        .withSchemaName("nakka.cart-events.v2")
        .schemaDefinition
        .fingerprint
    }

    "keep its settings through copies" in {
      val inlet = JsonInlet[ItemAdded]("in").withSchemaName("s").withUniqueGroupId
      inlet.schemaName mustBe "s"
      inlet.hasUniqueGroupId mustBe true
      JsonOutlet[ItemAdded]("out")
        .withSchemaName("s")
        .withPartitioner(_.cartId)
        .partitioner(ItemAdded("c", "p", 1)) mustBe
      "c"
    }
  }
}
