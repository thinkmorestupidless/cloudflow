package cloudflow.akkastreamsdoc

// tag::httpStreamingIngress[]
import JsonSupport._
import akka.http.scaladsl.common.EntityStreamingSupport
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import cloudflow.akkastream._
import cloudflow.akkastream.util.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

class DataStreamingIngress extends AkkaServerStreamlet {
  val out   = AvroOutlet[Data]("out", RoundRobinPartitioner)
  def shape = StreamletShape.withOutlets(out)

  implicit val entityStreamingSupport: EntityStreamingSupport = EntityStreamingSupport.json()
  override def createLogic            = HttpServerLogic.defaultStreaming(this, out)
}
// end::httpStreamingIngress[]
