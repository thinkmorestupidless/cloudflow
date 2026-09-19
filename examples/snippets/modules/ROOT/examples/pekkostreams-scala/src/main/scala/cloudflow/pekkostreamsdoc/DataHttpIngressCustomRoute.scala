package cloudflow.pekkostreamsdoc

import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.model._

import cloudflow.pekkostream._
import cloudflow.pekkostream.util.scaladsl._

import cloudflow.streamlets.avro._
import cloudflow.streamlets._

import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import JsonSupport._

// tag::customRoute[]
class DataHttpIngressCustomRoute extends PekkoServerStreamlet {
  val out   = AvroOutlet[Data]("out").withPartitioner(RoundRobinPartitioner)
  def shape = StreamletShape.withOutlets(out)
  def createLogic = new HttpServerLogic(this) {
    val writer = sinkRef(out)
    override def route(): Route =
      put {
        entity(as[Data]) { data =>
          onSuccess(writer.write(data)) { _ =>
            complete(StatusCodes.OK)
          }
        }
      }
  }
}
// end::customRoute[]
