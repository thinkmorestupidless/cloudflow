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

package cloudflow.pekkostream.testkit.scaladsl

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.ConsumerMessage._
import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl._

import cloudflow.streamlets._
import cloudflow.pekkostream.testkit._

case class SourceInletTap[T](inlet: CodecInlet[T], source: Source[(T, Committable), NotUsed]) extends InletTap[T] {
  def portName = inlet.name
}

/** An inlet tap fed with whole [[cloudflow.streamlets.Record Record]]s, so a test can give elements keys and headers. A
  * streamlet reading the inlet with a value source sees only the values.
  */
case class RecordSourceInletTap[T](inlet: CodecInlet[T], records: Source[(Record[T], Committable), NotUsed])
    extends InletTap[T] {
  def portName = inlet.name
  private[testkit] override def recordSource = records
  private[testkit] def source = records.map { case (record, committable) => (record.value, committable) }
}

/** An inlet tap with a queue of [[cloudflow.streamlets.Record Record]]s, so a test can offer elements with keys and
  * headers.
  */
case class RecordQueueInletTap[T](inlet: CodecInlet[T])(implicit system: ActorSystem) extends InletTap[T] {
  private val bufferSize = 1024
  private val hub = BroadcastHub.sink[Record[T]](bufferSize)
  private val qSource = Source.queue[Record[T]](bufferSize, OverflowStrategy.backpressure)
  private[testkit] val (q, src) = qSource.toMat(hub)(Keep.both).run()

  val portName = inlet.name
  private[testkit] override val recordSource = src.map(record => (record, TestCommittableOffset()))
  private[testkit] val source = recordSource.map { case (record, committable) => (record.value, committable) }
  val queue: SourceQueueWithComplete[Record[T]] = q
}

case class QueueInletTap[T](inlet: CodecInlet[T])(implicit system: ActorSystem) extends InletTap[T] {
  private val bufferSize = 1024
  private val hub = BroadcastHub.sink[T](bufferSize)
  private val qSource = Source.queue[T](bufferSize, OverflowStrategy.backpressure)
  private[testkit] val (q, src) = qSource.toMat(hub)(Keep.both).run()

  val portName = inlet.name
  val source = src.map { t =>
    (t, TestCommittableOffset())
  }
  val queue: SourceQueueWithComplete[T] = q
}
