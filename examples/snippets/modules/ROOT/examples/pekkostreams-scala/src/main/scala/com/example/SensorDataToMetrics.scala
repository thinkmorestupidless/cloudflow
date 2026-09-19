package com.example

import org.apache.pekko.stream.scaladsl.RunnableGraph
import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import cloudflow.pekkostream._
import cloudflow.pekkostream.scaladsl._
import cloudflow.pekkostreamsdoc._

object SensorDataToMetrics extends PekkoStreamlet {
  val in: CodecInlet[Data]           = AvroInlet[Data]("in")
  val out: CodecOutlet[Data]         = AvroOutlet[Data]("out")
  override val shape: StreamletShape = StreamletShape.withInlets(in).withOutlets(out)

  override def createLogic: PekkoStreamletLogic = new RunnableGraphStreamletLogic {
    override final def runnableGraph: RunnableGraph[_] =
      sourceWithCommittableContext(in)
        .map(i => i)
        .to(committableSink(out))
  }
}
