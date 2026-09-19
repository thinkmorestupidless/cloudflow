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

package cloudflow.akkastream.testkit.scaladsl

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.ConsumerMessage._
import org.apache.pekko.stream._
import org.apache.pekko.stream.scaladsl._

import cloudflow.streamlets._
import cloudflow.akkastream.testkit._

case class SourceInletTap[T](inlet: CodecInlet[T], source: Source[(T, Committable), NotUsed]) extends InletTap[T] {
  def portName = inlet.name
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
