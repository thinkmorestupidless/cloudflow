package com.example

import org.apache.pekko.actor._
import org.apache.pekko.testkit._

import org.scalatest._
import org.scalatest.wordspec._
import org.scalatest.matchers.must._
import cloudflow.pekkostream.testkit.scaladsl._
import cloudflow.pekkostreamsdoc.RecordSumFlow

class SampleSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private implicit val system: ActorSystem = ActorSystem("PekkoStreamletSpec")

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  "An TestProcessor" should {

    //tag::config-value[]
    val testkit =
      PekkoStreamletTestKit(system).withConfigParameterValues(ConfigParameterValue(RecordSumFlow.recordsInWindowParameter, "20"))
    //end::config-value[]

    "Allow for creating a 'flow processor'" in {
      val a = 1
      a must equal(1)
    }
  }
}
