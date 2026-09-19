package connectedcar.streamlet

import org.apache.pekko.actor._
import org.apache.pekko.cluster.typed.{ Cluster, Join }
import org.apache.pekko.stream.scaladsl._
import org.apache.pekko.testkit._
import org.scalatest._
import org.scalatest.wordspec._
import org.scalatest.matchers.must._
import cloudflow.pekkostream.testkit.scaladsl._
import connectedcar.data._
import connectedcar.streamlets.ConnectedCarCluster
import connectedcar.streamlets.RawCarDataGenerator.generateCarERecord
import org.apache.pekko.actor.typed.scaladsl.adapter._

class ConnectedCarClusterTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private implicit val system: ActorSystem = ActorSystem("PekkoStreamletSpec")
  private val cluster                      = Cluster(system.toTyped)

  override def beforeAll(): Unit =
    cluster.manager ! Join(cluster.selfMember.address)

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "A ConnectedCarCluster streamlet" should {

    val testkit = PekkoStreamletTestKit(system)
    // flaky test
    "Allow for creating a 'flow processor'" in {
      val record = generateCarERecord()
      val data   = Vector(record)

      val agg          = ConnectedCarAgg(record.carId, record.driver, record.speed, 1)
      val expectedData = Vector(agg)
      val source       = Source(data)
      val proc         = new ConnectedCarCluster
      val in           = testkit.inletFromSource(proc.in, source)
      val out          = testkit.outletAsTap(proc.out)

      testkit.run(proc, in, out, () => out.probe.receiveN(1) mustBe expectedData.map(d => proc.out.partitioner(d) -> d))

      out.probe.expectMsg(Completed)
    }
  }

}
