package connectedcar.streamlets

import cloudflow.pekkostream.PekkoStreamlet
import cloudflow.pekkostream.scaladsl.{ FlowWithCommittableContext, RunnableGraphStreamletLogic }
import cloudflow.streamlets.StreamletShape
import cloudflow.streamlets.avro.AvroInlet
import connectedcar.data.ConnectedCarAgg

object CarDataPrinter extends PekkoStreamlet {
  val in    = AvroInlet[ConnectedCarAgg]("in")
  val shape = StreamletShape(in)

  override def createLogic = new RunnableGraphStreamletLogic() {
    val flow = FlowWithCommittableContext[ConnectedCarAgg]()
      .map { record =>
        log.info("CarId: " + record.carId)
      }

    def runnableGraph =
      sourceWithCommittableContext(in).via(flow).to(committableSink)
  }
}
