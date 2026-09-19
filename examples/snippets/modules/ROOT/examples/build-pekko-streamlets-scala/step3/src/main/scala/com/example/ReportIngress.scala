package com.example

import org.apache.pekko.stream.scaladsl.Source

import cloudflow.streamlets._
import cloudflow.streamlets.avro._

import cloudflow.pekkostream._
import cloudflow.pekkostream.scaladsl._
import scala.concurrent.duration._

object ReportIngress extends PekkoStreamlet {
  // 1. Create inlets and outlets
  val outlet = AvroOutlet[Report]("out", _.id)

  // 2. Define the shape of the streamlet
  val shape = StreamletShape.withOutlets(outlet)

  // 3. TODO Override createLogic to provide StreamletLogic
  def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph =
      Source.tick(0.seconds, 2.seconds, Report("abc", "test", "Just a test", List("ab", "bc"))).to(plainSink(outlet))
  }
}
