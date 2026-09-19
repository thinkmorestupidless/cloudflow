package com.example

import org.apache.pekko.stream.scaladsl._

import cloudflow.streamlets._
import cloudflow.pekkostream._
import cloudflow.streamlets.avro._
import cloudflow.pekkostream.scaladsl._

import scala.concurrent.duration._

import cloudflow.pekkostreamsdoc.Data

object SensorDataIngress extends PekkoStreamlet {
  val out                  = AvroOutlet[Data]("out")
  override final val shape = StreamletShape.withOutlets(out)

  final override def createLogic = new RunnableGraphStreamletLogic {
    override final def runnableGraph =
      Source.tick(0.seconds, 10.milliseconds, Data("test", 2)).to(plainSink(out))
  }
}
