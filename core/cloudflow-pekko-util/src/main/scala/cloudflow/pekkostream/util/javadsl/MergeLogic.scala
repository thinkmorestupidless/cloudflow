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

package cloudflow.pekkostream.util.javadsl

import scala.annotation.varargs
import scala.jdk.CollectionConverters._
import org.apache.pekko.kafka.ConsumerMessage._
import cloudflow._
import cloudflow.pekkostream._
import cloudflow.streamlets._

/** Java API Merges two or more sources, or inlets, of the same type, into one source.
  */
object Merger {

  /** Java API Merges two or more sources into one source. Elements from all inlets will be processed with at-least-once
    * semantics. The elements will be processed in semi-random order and with equal priority for all sources.
    */
  def source[T](sources: java.util.List[org.apache.pekko.stream.javadsl.SourceWithContext[T, Committable, _]])
      : org.apache.pekko.stream.javadsl.SourceWithContext[T, Committable, _] =
    cloudflow.pekkostream.util.scaladsl.Merger.source(sources.asScala.map(_.asScala).toSeq).asJava

  /** Java API Merges two or more inlets into one source. Elements from all inlets will be processed with at-least-once
    * semantics. The elements will be processed in semi-random order and with equal priority for all inlets.
    */
  def source[T](
      context: PekkoStreamletContext,
      inlets: java.util.List[CodecInlet[T]]): org.apache.pekko.stream.javadsl.SourceWithContext[T, Committable, _] =
    cloudflow.pekkostream.util.scaladsl.Merger.source(inlets.asScala.toSeq)(context).asJava

  @varargs
  def source[T](
      context: PekkoStreamletContext,
      inlet: CodecInlet[T],
      inlets: CodecInlet[T]*): org.apache.pekko.stream.javadsl.SourceWithContext[T, Committable, _] =
    cloudflow.pekkostream.util.scaladsl.Merger.source(inlet +: inlets)(context).asJava
}

/** Java API A `MergeLogic` merges two or more inlets into one outlet. Elements from all inlets will be processed with
  * at-least-once semantics. The elements will be processed in semi-random order and with equal priority for all inlets.
  */
@deprecated("Use `Merger.source` instead.", "1.3.1")
final class MergeLogic[T](
    inletPorts: java.util.List[CodecInlet[T]],
    outlet: CodecOutlet[T],
    context: PekkoStreamletContext)
    extends pekkostream.util.scaladsl.MergeLogic(inletPorts.asScala.toIndexedSeq, outlet)(context)
