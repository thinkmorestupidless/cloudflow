package com.example

import cloudflow.streamlets._
import cloudflow.pekkostream._
import cloudflow.streamlets.avro._
import cloudflow.pekkostream.scaladsl._

import cloudflow.pekkostreamsdoc.Data

object MetricsEgress extends PekkoStreamlet {
  val in                   = AvroInlet[Data]("in")
  final override val shape = StreamletShape.withInlets(in)

  final override def createLogic = new RunnableGraphStreamletLogic {
    override final def runnableGraph =
      sourceWithCommittableContext(in)
        .map { i =>
          println(s"Int: ${i.value}"); i
        }
        .to(committableSink(defaultCommitterSettings))
  }
}
