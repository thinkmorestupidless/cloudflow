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
import org.apache.pekko.actor._
import org.apache.pekko.stream.scaladsl._
import com.typesafe.config._

import cloudflow.streamlets._

import cloudflow.pekkostream.testkit._

object PekkoStreamletTestKit {
  def apply(sys: ActorSystem): PekkoStreamletTestKit = new PekkoStreamletTestKit(sys)
  def apply(sys: ActorSystem, config: Config): PekkoStreamletTestKit = new PekkoStreamletTestKit(sys, config)
}

/** Testkit for testing pekko streamlets.
  *
  * API:
  *
  * {{{
  * // instantiate the testkit
  * val testkit = PekkoStreamletTestKit(system)
  *
  * // setup inlet and outlet
  * val in = testkit.inletAsQueue(SimpleFlowProcessor.shape.inlet)
  * val out = testkit.outletAsProbe(SimpleFlowProcessor.shape.outlet)
  *
  * // put data
  * in.queue.offer(Data(1, "a"))
  * in.queue.offer(Data(2, "b"))
  *
  * // run the testkit
  * testkit.run(SimpleFlowProcessor, in, out, () => {
  *   out.probe.expectMsg(("2", Data(2, "b")))
  * })
  * }}}
  *
  * The following point is from `org.apache.pekko.testkit.Testkit` and is valid mostly for this testkit as well:
  *
  * Beware of two points:
  *
  *   - the ActorSystem passed into the constructor needs to be shutdown, otherwise thread pools and memory will be
  *     leaked
  *   - this class is not thread-safe (only one actor with one queue, one stack of `within` blocks); it is expected that
  *     the code is executed from a constructor as shown above, which makes this a non-issue, otherwise take care not to
  *     run tests within a single test class instance in parallel.
  *
  * It should be noted that for CI servers and the like all maximum Durations are scaled using their Duration.dilated
  * method, which uses the TestKitExtension.Settings.TestTimeFactor settable via pekko.conf entry
  * "pekko.test.timefactor".
  */
final case class PekkoStreamletTestKit private[testkit] (
    system: ActorSystem,
    config: Config = ConfigFactory.empty(),
    volumeMounts: List[VolumeMount] = List.empty)
    extends BasePekkoStreamletTestKit[PekkoStreamletTestKit] {

  def withConfig(c: Config): PekkoStreamletTestKit = this.copy(config = c)

  def withVolumeMounts(volumeMount: VolumeMount, volumeMounts: VolumeMount*): PekkoStreamletTestKit =
    copy(volumeMounts = volumeMount +: volumeMounts.toList)

  /** */
  def inletAsTap[T](inlet: CodecInlet[T]): QueueInletTap[T] =
    QueueInletTap[T](inlet)(system)

  /** */
  def inletFromSource[T](inlet: CodecInlet[T], source: Source[T, NotUsed]): SourceInletTap[T] =
    SourceInletTap[T](inlet, source.map(t => (t, TestCommittableOffset())))

  /** An inlet tap whose queue takes [[cloudflow.streamlets.Record Record]]s, so elements can carry a key and headers
    * into a streamlet that reads the inlet with a record source.
    */
  def inletAsRecordTap[T](inlet: CodecInlet[T]): RecordQueueInletTap[T] =
    RecordQueueInletTap[T](inlet)(system)

  /** An inlet tap fed from a source of [[cloudflow.streamlets.Record Record]]s. */
  def inletFromRecordSource[T](inlet: CodecInlet[T], source: Source[Record[T], NotUsed]): RecordSourceInletTap[T] =
    RecordSourceInletTap[T](inlet, source.map(r => (r, TestCommittableOffset())))

  /** An outlet tap whose probe receives each element written to the outlet as a [[cloudflow.streamlets.Record Record]]:
    * its value, its key — the record's own for a record sink, otherwise the outlet's partitioner's, `None` if that gave
    * none — and its headers.
    *
    * {{{
    * out.probe.expectMsg(Record(Data(2, "b"), Some("2"), List(Header("ce_type", "created"))))
    * }}}
    */
  def outletAsRecordTap[T](outlet: CodecOutlet[T]): RecordProbeOutletTap[T] =
    RecordProbeOutletTap[T](outlet)(system)

  /** Creates an outlet tap. An outlet tap provides a probe that can be used to assert elements produced to the
    * specified outlet.
    *
    * The data being written to the outlet will always be partitioned using the partitioner function of the outlet. This
    * means that assertions should always expect a Scala tuple with the first element being the partitioning key (can be
    * null in case the default RoundRobinPartitioner is used) and the second element being the actual data element.
    *
    * Example (see the full example above, on the class level:
    *
    * {{{
    * val testkit = PekkoStreamletTestKit(system)
    * val out = testkit.outletAsProbe(SimpleFlowProcessor.shape.outlet)
    *
    * ...
    *
    * testkit.run(SimpleFlowProcessor, in, out, () => {
    *   out.probe.expectMsg(("2", Data(2, "b")))
    * })
    * }}}
    */
  def outletAsTap[T](outlet: CodecOutlet[T]): ProbeOutletTap[T] =
    ProbeOutletTap[T](outlet)(system)

  /** Attaches the provided Sink to the specified outlet.
    *
    * The data being written to the Sink will always be partitioned using the partitioner function of the outlet. This
    * means that the Sink should expect Scala tuples, with the first element being the partitioning key (can be null in
    * case the default RoundRobinPartitioner is used) and the second element being the actual data element.
    *
    * This method can be used to for instance quickly collect all output produced into a simple sequence using
    * `Sink.seq[T]`.
    */
  def outletToSink[T](outlet: CodecOutlet[T], sink: Sink[Tuple2[String, T], NotUsed]): SinkOutletTap[T] =
    SinkOutletTap[T](outlet, sink)
}
