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

package cloudflow.pekkostream.util.scaladsl

import scala.concurrent.duration._

import org.apache.pekko.actor._
import org.apache.pekko.stream.scaladsl._
import org.apache.pekko.testkit._

import org.scalatest._
import org.scalatest.wordspec._
import org.scalatest.matchers.must._

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.pekkostream._
import cloudflow.pekkostream.scaladsl._
import cloudflow.pekkostream.testdata._
import cloudflow.pekkostream.testkit.scaladsl._

/** The record API through the testkit: keys and headers in, keys and headers out. */
class RecordStreamletSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private implicit val system: ActorSystem = ActorSystem("RecordStreamletSpec")
  private val timeout = 10.seconds.dilated
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val testkit = PekkoStreamletTestKit(system)

  /** Upper-cases each name and adds a `stage` header, keeping whatever key and headers arrived. */
  object Upper extends PekkoStreamlet {
    val in = AvroInlet[Data]("in")
    val out = AvroOutlet[Data]("out")
    final override val shape = StreamletShape.withInlets(in).withOutlets(out)
    override def createLogic = new RunnableGraphStreamletLogic() {
      def runnableGraph =
        recordSourceWithCommittableContext(in)
          .map(record => record.map(d => d.copy(name = d.name.toUpperCase)).withHeader("stage", "upper"))
          .to(committableRecordSink(out))
    }
  }

  "A streamlet using the committable record source and sink" should {
    "pass each record's key and headers through, in order, with its own header after them" in {
      val in = testkit.inletAsRecordTap(Upper.in)
      val out = testkit.outletAsRecordTap(Upper.out)

      testkit.run(
        Upper,
        in,
        out,
        () => {
          in.queue.offer(Record(Data(1, "a"), Some("cart-1"), List(Header("ce_type", "ItemAdded"))))
          in.queue.offer(Record(Data(2, "b"), Some("cart-2"), List(Header("ce_type", "CheckedOut"))))
          out.probe.expectMsg(
            timeout,
            Record(Data(1, "A"), Some("cart-1"), List(Header("ce_type", "ItemAdded"), Header("stage", "upper"))))
          out.probe.expectMsg(
            timeout,
            Record(Data(2, "B"), Some("cart-2"), List(Header("ce_type", "CheckedOut"), Header("stage", "upper"))))
        })
    }

    "key a record that has no key by the outlet's partitioner, as a plain element would be" in {
      object ById extends PekkoStreamlet {
        val in = AvroInlet[Data]("in")
        val out = AvroOutlet[Data]("out").withPartitioner(_.id.toString)
        final override val shape = StreamletShape.withInlets(in).withOutlets(out)
        override def createLogic = new RunnableGraphStreamletLogic() {
          def runnableGraph = recordSourceWithCommittableContext(in).to(committableRecordSink(out))
        }
      }
      val in = testkit.inletAsRecordTap(ById.in)
      val out = testkit.outletAsRecordTap(ById.out)

      testkit.run(
        ById,
        in,
        out,
        () => {
          in.queue.offer(Record(Data(7, "keyless")))
          in.queue.offer(Record(Data(8, "keyed"), Some("own-key")))
          out.probe.expectMsg(timeout, Record(Data(7, "keyless"), Some("7")))
          out.probe.expectMsg(timeout, Record(Data(8, "keyed"), Some("own-key")))
        })
    }

    "read a value-only tap as records with no key and no headers" in {
      val in = testkit.inletAsTap(Upper.in)
      val out = testkit.outletAsRecordTap(Upper.out)

      testkit.run(
        Upper,
        in,
        out,
        () => {
          in.queue.offer(Data(3, "c"))
          out.probe.expectMsg(timeout, Record(Data(3, "C"), None, List(Header("stage", "upper"))))
        })
    }

    "report its output to a value outlet tap as the usual (key, value) pairs" in {
      val in = testkit.inletAsRecordTap(Upper.in)
      val out = testkit.outletAsTap(Upper.out)

      testkit.run(
        Upper,
        in,
        out,
        () => {
          in.queue.offer(Record(Data(4, "d"), Some("cart-4")))
          out.probe.expectMsg(timeout, ("cart-4", Data(4, "D")))
        })
    }
  }

  "A streamlet using the plain record source and sink" should {
    "pass each record's key and headers through" in {
      object Plain extends PekkoStreamlet {
        val in = AvroInlet[Data]("in")
        val out = AvroOutlet[Data]("out")
        final override val shape = StreamletShape.withInlets(in).withOutlets(out)
        override def createLogic = new RunnableGraphStreamletLogic() {
          def runnableGraph = plainRecordSource(in).to(plainRecordSink(out))
        }
      }
      val in = testkit.inletFromRecordSource(
        Plain.in,
        Source(List(Record(Data(5, "e"), Some("k5"), List(Header("ce_id", "abc"))))))
      val out = testkit.outletAsRecordTap(Plain.out)

      testkit.run(
        Plain,
        in,
        out,
        () => out.probe.expectMsg(timeout, Record(Data(5, "e"), Some("k5"), List(Header("ce_id", "abc")))))
    }
  }
}
