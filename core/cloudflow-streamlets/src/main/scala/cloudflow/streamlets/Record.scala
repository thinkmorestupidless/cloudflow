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
import java.util.{ Arrays, Optional }

import scala.collection.immutable
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

/** A Kafka record header. Kafka header values are bytes; CloudEvents attributes in Kafka's binary content mode
  * (`ce_type`, `ce_id`, ...) are UTF-8 strings, which [[valueAsString]] reads.
  *
  * Equality compares the value's bytes, not the array's identity.
  */
final case class Header(key: String, value: Array[Byte]) {
  def valueAsString: String = new String(value, UTF_8)

  /** Java API */
  def getKey(): String = key

  /** Java API */
  def getValue(): Array[Byte] = value

  /** Java API */
  def getValueAsString(): String = valueAsString

  override def equals(other: Any): Boolean = other match {
    case Header(k, v) => k == key && Arrays.equals(v, value)
    case _            => false
  }
  override def hashCode: Int = 31 * key.hashCode + Arrays.hashCode(value)
  override def toString: String = s"Header($key, $valueAsString)"
}

object Header {

  /** A header whose value is the UTF-8 encoding of `value`. */
  def apply(key: String, value: String): Header = new Header(key, value.getBytes(UTF_8))

  /** Java API */
  def create(key: String, value: String): Header = apply(key, value)

  /** Java API */
  def create(key: String, value: Array[Byte]): Header = new Header(key, value)
}

/** A decoded element together with the Kafka record's key and headers.
  *
  * Read with the record sources of a streamlet logic (`recordSourceWithCommittableContext`, `plainRecordSource`), and
  * write with the record sinks (`committableRecordSink`, `plainRecordSink`). Passing a record's key through to an
  * outlet keeps every element with the same key on the same partition downstream, which is what preserves per-key
  * ordering across a pipeline stage.
  *
  * @param value
  *   the decoded element.
  * @param key
  *   the record key, UTF-8 decoded; `None` for a record without one. On write, a record without a key is keyed by the
  *   outlet's partitioner, as a plain element would be.
  * @param headers
  *   the record headers, in order. Kafka permits repeated keys; [[header]] returns the last, as Kafka's own
  *   `lastHeader` does.
  */
final case class Record[+T](value: T, key: Option[String] = None, headers: immutable.Seq[Header] = Nil) {

  /** The last header with this key, if any. */
  def header(name: String): Option[Header] = headers.findLast(_.key == name)

  /** The last header with this key, UTF-8 decoded, if any. */
  def headerValue(name: String): Option[String] = header(name).map(_.valueAsString)

  /** The same key and headers around a different value: the usual shape of a transformation. */
  def withValue[U](newValue: U): Record[U] = Record(newValue, key, headers)

  def map[U](f: T => U): Record[U] = withValue(f(value))

  def withKey(newKey: String): Record[T] = Record(value, Option(newKey), headers)

  def withoutKey: Record[T] = Record(value, None, headers)

  def withHeaders(newHeaders: immutable.Seq[Header]): Record[T] = Record(value, key, newHeaders)

  /** Adds a header after the existing ones. */
  def withHeader(header: Header): Record[T] = Record(value, key, headers :+ header)

  /** Adds a UTF-8 string header after the existing ones. */
  def withHeader(name: String, value: String): Record[T] = withHeader(Header(name, value))

  /** Java API */
  def getValue(): T = value

  /** Java API */
  def getKey(): Optional[String] = key.toJava

  /** Java API */
  def getHeaders(): java.util.List[Header] = headers.asJava

  /** Java API */
  def getHeader(name: String): Optional[Header] = header(name).toJava

  /** Java API */
  def getHeaderValue(name: String): Optional[String] = headerValue(name).toJava
}

object Record {

  /** Java API: a record with no key and no headers. */
  def create[T](value: T): Record[T] = Record(value)

  /** Java API: a keyed record with no headers. */
  def create[T](key: String, value: T): Record[T] = Record(value, Option(key))

  /** Java API */
  def create[T](key: String, value: T, headers: java.util.List[Header]): Record[T] =
    Record(value, Option(key), headers.asScala.toList)
}
