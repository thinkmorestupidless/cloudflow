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

package cloudflow.streamlets.avro

import java.io.ByteArrayOutputStream
import scala.util._
import org.apache.avro.Schema
import org.apache.avro.io.{ DecoderFactory, EncoderFactory }
import org.apache.avro.specific.{ SpecificDatumReader, SpecificDatumWriter, SpecificRecordBase }
import cloudflow.streamlets._

/** Avro binary codec. The Twitter Bijection dependency (com.twitter:bijection-avro) has no Scala 3 artifact, so the
  * encode/decode logic is inlined here using Apache Avro directly.
  */
class AvroCodec[T <: SpecificRecordBase](avroSchema: Schema) extends Codec[T] {

  def encode(value: T): Array[Byte] = {
    val writer = new SpecificDatumWriter[T](avroSchema)
    val bos = new ByteArrayOutputStream()
    val encoder = EncoderFactory.get().binaryEncoder(bos, null)
    writer.write(value, encoder)
    encoder.flush()
    bos.toByteArray
  }

  def decode(bytes: Array[Byte]): Try[T] = Try {
    val reader = new SpecificDatumReader[T](avroSchema)
    val decoder = DecoderFactory.get().binaryDecoder(bytes, null)
    reader.read(null.asInstanceOf[T], decoder)
  }

  def schema: Schema = avroSchema
}
