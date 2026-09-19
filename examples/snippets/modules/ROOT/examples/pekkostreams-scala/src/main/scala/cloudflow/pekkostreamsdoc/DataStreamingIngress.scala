package cloudflow.pekkostreamsdoc

// tag::httpStreamingIngress[]
import JsonSupport._
import org.apache.pekko.http.scaladsl.common.EntityStreamingSupport
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import cloudflow.pekkostream._
import cloudflow.pekkostream.util.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._

class DataStreamingIngress extends PekkoServerStreamlet {
  val out   = AvroOutlet[Data]("out", RoundRobinPartitioner)
  def shape = StreamletShape.withOutlets(out)

  implicit val entityStreamingSupport: EntityStreamingSupport = EntityStreamingSupport.json()
  override def createLogic                                    = HttpServerLogic.defaultStreaming(this, out)
}
// end::httpStreamingIngress[]
