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

package cloudflow.blueprint

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64

import org.scalatest.matchers.must._
import org.scalatest.wordspec._

/** JSON ports connect by schema name. cloudflow-json declares them as below (cloudflow-blueprint cannot depend on it:
  * it also builds for Scala 2.12), so these descriptors are what verification sees from a JSON inlet or outlet.
  */
class JsonSchemaVerificationSpec extends AnyWordSpec with Matchers {
  import BlueprintBuilder._

  case class Foo(name: String)

  private def json(schemaName: String) =
    SchemaDescriptor(
      schemaName,
      schemaName,
      Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(schemaName.getBytes(UTF_8))),
      "json")

  private def ingress(schema: SchemaDescriptor) =
    randomStreamlet().asIngress(Vector(OutletDescriptor("out", schema)))

  private def egress(schema: SchemaDescriptor) =
    randomStreamlet().asBox.copy(inlets = Vector(InletDescriptor("in", schema)))

  private def connect(out: SchemaDescriptor, in: SchemaDescriptor) = {
    val from = ingress(out)
    val to = egress(in)
    val fromRef = from.randomRef
    val toRef = to.randomRef
    Blueprint()
      .define(Vector(from, to))
      .use(fromRef)
      .use(toRef)
      .connect(Topic("events"), fromRef.out, toRef.in)
  }

  "A JSON outlet and inlet" should {
    "verify when they name the same schema" in {
      connect(json("nakka.cart-events.v1"), json("nakka.cart-events.v1")).problems mustBe empty
    }

    "fail verification when they name different schemas — a new version of a contract is a new name" in {
      connect(json("nakka.cart-events.v1"), json("nakka.cart-events.v2")).problems.collect {
        case problem: IncompatibleSchema => problem
      } must have size 1
    }

    "never verify against another format, even with the same name" in {
      val avro = createSchemaDescriptor[Foo]("nakka.cart-events.v1")
      connect(json("nakka.cart-events.v1"), avro).problems.collect { case problem: IncompatibleSchema =>
        problem
      } must have size 1
    }
  }
}
