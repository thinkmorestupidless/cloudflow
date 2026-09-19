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
import java.security.MessageDigest
import java.util.Base64

import scala.reflect.ClassTag
import scala.util.Try

import com.github.plokhotnyuk.jsoniter_scala.core._

import cloudflow.streamlets._

/** Encodes and decodes JSON with a jsoniter-scala `JsonValueCodec` — the library, and usually the codecs, nakka uses
  * for the events it publishes.
  */
final class JsonCodec[T](implicit valueCodec: JsonValueCodec[T]) extends Codec[T] {
  def encode(value: T): Array[Byte] = writeToArray(value)
  def decode(bytes: Array[Byte]): Try[T] = Try(readFromArray[T](bytes))
}

/** JSON carries no schema, so a JSON port declares a *schema name* instead: the name of the contract its data follows,
  * such as `nakka.cart-events.v1`. Blueprint verification connects two JSON ports only if their schema names are equal
  * (it compares fingerprints, and the fingerprint is derived from the name alone). Versioning the name is how a
  * breaking change to the contract becomes a verification failure rather than a decoding failure in production.
  *
  * By default the schema name is the element type's fully qualified class name, so two streamlets sharing a type
  * connect with no configuration; name the contract explicitly with `withSchemaName` when the data comes from, or goes
  * to, something that is not a Scala type — another service's topic.
  */
object JsonSchema {
  val Format = "json"

  def defaultName[T](implicit classTag: ClassTag[T]): String = classTag.runtimeClass.getName

  def definition(schemaName: String): SchemaDefinition =
    SchemaDefinition(name = schemaName, schema = schemaName, fingerprint = fingerprint(schemaName), format = Format)

  def fingerprint(schemaName: String): String =
    Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(schemaName.getBytes(UTF_8)))
}

/** An inlet that reads JSON. See [[JsonSchema]] for what `schemaName` is.
  *
  * {{{
  * given JsonValueCodec[CartEvent] = JsonCodecMaker.make
  * val in = JsonInlet[CartEvent]("in").withSchemaName("nakka.cart-events.v1")
  * }}}
  *
  * A record that does not decode goes to the `errorHandler`, which by default logs it and skips it.
  */
final case class JsonInlet[T](
    name: String,
    schemaName: String,
    hasUniqueGroupId: Boolean = false,
    errorHandler: (Array[Byte], Throwable) => Option[T] = CodecInlet.logAndSkip[T](_: Array[Byte], _: Throwable))(
    implicit valueCodec: JsonValueCodec[T])
    extends CodecInlet[T] {
  val codec: Codec[T] = new JsonCodec[T]
  def schemaDefinition: SchemaDefinition = JsonSchema.definition(schemaName)
  def schemaAsString: String = schemaName
  def withUniqueGroupId: JsonInlet[T] = copy(hasUniqueGroupId = true)
  def withSchemaName(newSchemaName: String): JsonInlet[T] = copy(schemaName = newSchemaName)
  override def withErrorHandler(handler: (Array[Byte], Throwable) => Option[T]): JsonInlet[T] =
    copy(errorHandler = handler)
}

object JsonInlet {

  /** An inlet whose schema name is `T`'s fully qualified class name. */
  def apply[T](name: String)(implicit valueCodec: JsonValueCodec[T], classTag: ClassTag[T]): JsonInlet[T] =
    JsonInlet[T](name, JsonSchema.defaultName[T])
}

/** An outlet that writes JSON. See [[JsonSchema]] for what `schemaName` is. Elements are keyed by `partitioner`, as for
  * any outlet, unless they are written as [[cloudflow.streamlets.Record Record]]s carrying their own key.
  */
final case class JsonOutlet[T](name: String, schemaName: String, partitioner: T => String = RoundRobinPartitioner)(
    implicit valueCodec: JsonValueCodec[T])
    extends CodecOutlet[T] {
  val codec: Codec[T] = new JsonCodec[T]
  def schemaDefinition: SchemaDefinition = JsonSchema.definition(schemaName)
  def schemaAsString: String = schemaName
  def withPartitioner(newPartitioner: T => String): JsonOutlet[T] = copy(partitioner = newPartitioner)
  def withSchemaName(newSchemaName: String): JsonOutlet[T] = copy(schemaName = newSchemaName)
}

object JsonOutlet {

  /** An outlet whose schema name is `T`'s fully qualified class name. */
  def apply[T](name: String)(implicit valueCodec: JsonValueCodec[T], classTag: ClassTag[T]): JsonOutlet[T] =
    JsonOutlet[T](name, JsonSchema.defaultName[T])
}
