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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.pekko.actor._
import org.apache.pekko.stream.scaladsl._
import org.apache.pekko.testkit._

import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.must._
import org.scalatest.wordspec._

import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.pekkostream._
import cloudflow.pekkostream.scaladsl._
import cloudflow.pekkostream.testdata._
import cloudflow.pekkostream.testkit.scaladsl._

/** The commit-after-write sink through the testkit: batching, order, one write at a time. What it commits is only
  * visible against real Kafka (`SinkCommittingAfterKafkaSpec`).
  */
class SinkCommittingAfterSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with Eventually {

  private implicit val system: ActorSystem = ActorSystem("SinkCommittingAfterSpec")
  import system.dispatcher
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(10.seconds.dilated, 20.millis)

  private val testkit = PekkoStreamletTestKit(system)

  /** Writes slowly, recording each batch and the most writes it ever saw in flight at once. */
  final class Writer(batchSize: Int) extends PekkoStreamlet {
    val in = AvroInlet[Data]("in")
    final override val shape = StreamletShape.withInlets(in)
    val batches = new ConcurrentLinkedQueue[immutable.Seq[Int]]()
    val inFlight = new AtomicInteger(0)
    val maxInFlight = new AtomicInteger(0)

    private def write(batch: immutable.Seq[Data]): Future[Unit] = {
      maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), math.max)
      Future {
        Thread.sleep(20) // long enough that a second write, if one were started, would overlap this one
        batches.add(batch.map(_.id))
        inFlight.decrementAndGet()
        ()
      }
    }

    override def createLogic = new RunnableGraphStreamletLogic() {
      def runnableGraph =
        sourceWithCommittableContext(in).to(sinkCommittingAfter(write, batchSize = batchSize, batchWithin = 5.seconds))
    }
  }

  "A sink committing after its write" should {
    "write in batches of at most its batch size, in the order the elements were read, flushing the rest at the end" in {
      val writer = new Writer(batchSize = 3)
      val in = testkit.inletFromSource(writer.in, Source((0 until 7).map(i => Data(i, s"n$i"))))

      testkit.run(
        writer,
        in,
        () => eventually(writer.batches.asScala.toList mustBe List(List(0, 1, 2), List(3, 4, 5), List(6))))
    }

    "never have two writes in flight at once" in {
      val writer = new Writer(batchSize = 2)
      val in = testkit.inletFromSource(writer.in, Source((0 until 20).map(i => Data(i, s"n$i"))))

      testkit.run(
        writer,
        in,
        () => {
          eventually(writer.batches.asScala.flatten.toList mustBe (0 until 20).toList)
          writer.maxInFlight.get mustBe 1
        })
    }
  }
}
