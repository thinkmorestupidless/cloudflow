package sensordata

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import cloudflow.pekkostream.WritableSinkRef

import sensordata.grpc.{ SensorData, SensorDataService, SensorReply }

import scala.concurrent.{ ExecutionContext, Future }

class SensorDataServiceImpl(sink: WritableSinkRef[SensorData])(implicit ec: ExecutionContext) extends SensorDataService {
  override def provide(in: SensorData): Future[SensorReply] = {
    println("howdy")
    sink.write(in).map(_ => { println("mapped"); SensorReply(s"Received ${in.payload}") })
  }
}
