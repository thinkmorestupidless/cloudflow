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

package cloudflow.pekkostream

package testkit {
  import scala.collection.immutable
  import scala.util.Try
  import scala.concurrent.{ Future, Promise }
  import org.apache.pekko.{ Done, NotUsed }
  import org.apache.pekko.stream.scaladsl._
  import cloudflow.streamlets.{ CodecOutlet, Header, Record }
  import org.apache.pekko.kafka.ConsumerMessage._

  trait InletTap[T] {
    def portName: String

    // This is for internal usage so using a scaladsl Source and a Tuple is no problem
    private[testkit] def source: Source[(T, Committable), NotUsed]

    // What a record source reads: a value-only tap's elements arrive with no key and no headers.
    private[testkit] def recordSource: Source[(Record[T], Committable), NotUsed] =
      source.map { case (value, committable) => (Record(value), committable) }
  }

  trait OutletTap[T] {
    def outlet: CodecOutlet[T]
    def portName: String = outlet.name
    private[testkit] def flow: Flow[PartitionedValue[T], PartitionedValue[T], NotUsed]
    // This is for internal usage so using a scaladsl Source is no problem
    private[testkit] def sink: Sink[PartitionedValue[T], Future[Done]]

    private[testkit] def toPartitionedValue(element: T): PartitionedValue[T] = {
      PartitionedValue(outlet.partitioner(element), element, Promise.successful(element))
    }

    private[testkit] def toPartitionedValue(element: T, promise: Promise[T]): PartitionedValue[T] =
      PartitionedValue(outlet.partitioner(element), element, promise)

    // Keyed as the record sinks key a Kafka record: the record's own key, else the outlet's partitioner.
    private[testkit] def toPartitionedValue(record: Record[T]): PartitionedValue[T] =
      PartitionedValue(
        record.key.getOrElse(outlet.partitioner(record.value)),
        record.value,
        Promise.successful(record.value),
        record.headers)
  }

  /** A representation of a key-value pair that is not bound to the Scala or Java DSLs
    */
  private[testkit] case class PartitionedValue[T](
      key: String,
      value: T,
      promise: Promise[T],
      headers: immutable.Seq[Header] = Nil) {
    def getKey(): String = key
    def getValue(): T = value
  }

  trait ConfigParameterValue {
    def configParameterKey: String
    def value: String
  }
}
