package sensordata

//tag::logic[]
import org.apache.pekko.grpc.scaladsl.ServerReflection
//end::logic[]
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.directives.RouteDirectives

import cloudflow.pekkostream._
//tag::logic[]
import cloudflow.pekkostream.util.scaladsl.GrpcServerLogic
//end::logic[]
import cloudflow.streamlets._
import cloudflow.streamlets.proto.ProtoOutlet

//tag::logic[]
import sensordata.grpc.{ SensorData, SensorDataService, SensorDataServiceHandler }

//end::logic[]

//tag::logic[]
class SensorDataIngress extends PekkoServerStreamlet {
  // ...

  //end::logic[]

  val out   = ProtoOutlet[SensorData]("out", RoundRobinPartitioner)
  def shape = StreamletShape.withOutlets(out)

//tag::logic[]
  override def createLogic = new GrpcServerLogic(this) {
    override def handlers() =
      List(SensorDataServiceHandler.partial(new SensorDataServiceImpl(sinkRef(out))), ServerReflection.partial(List(SensorDataService)))
  }
//end::logic[]
}
