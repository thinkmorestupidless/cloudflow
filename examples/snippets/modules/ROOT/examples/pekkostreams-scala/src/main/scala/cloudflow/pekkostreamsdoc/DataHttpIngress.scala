package cloudflow.pekkostreamsdoc

// tag::httpIngress[]
import cloudflow.pekkostream._
import cloudflow.pekkostream.util.scaladsl._

import cloudflow.streamlets.avro._
import cloudflow.streamlets._

import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import JsonSupport._

class DataHttpIngress extends PekkoServerStreamlet {
  val out         = AvroOutlet[Data]("out").withPartitioner(RoundRobinPartitioner)
  def shape       = StreamletShape.withOutlets(out)
  def createLogic = HttpServerLogic.default(this, out)
}
// end::httpIngress[]
