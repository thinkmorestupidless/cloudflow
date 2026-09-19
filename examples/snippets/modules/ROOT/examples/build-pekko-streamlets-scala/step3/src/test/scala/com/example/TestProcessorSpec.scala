package com.example

//tag::imports[]
import org.apache.pekko.actor._
import org.apache.pekko.stream.scaladsl._
import org.apache.pekko.testkit._

import org.scalatest._
import org.scalatest.wordspec._
import org.scalatest.matchers.must._

import cloudflow.pekkostream.testkit.scaladsl._
//end::imports[]

//tag::test[]
class TestProcessorSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private implicit val system: ActorSystem = ActorSystem("PekkoStreamletSpec")

  //tag::afterAll[]
  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
  //end::afterAll[]

  "A TestProcessor" should {

    val testkit = PekkoStreamletTestKit(system)

    "Allow for creating a 'flow processor'" in {
      val data         = Vector(Data(1, "a"), Data(2, "b"), Data(3, "c"))
      val expectedData = Vector(Data(2, "b"))
      val source       = Source(data)
      val proc         = new TestProcessor
      val in           = testkit.inletFromSource(proc.in, source)
      val out          = testkit.outletAsTap(proc.out)

      testkit.run(proc, in, out, () => out.probe.receiveN(1) mustBe expectedData.map(d => proc.out.partitioner(d) -> d))

      out.probe.expectMsg(Completed)
    }
  }
}
//end::test[]
