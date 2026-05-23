package cloudflow.akkastreamsdoc

import spray.json._

object JsonSupport extends DefaultJsonProtocol {

  implicit val dataFormat: RootJsonFormat[Data] = jsonFormat2(Data.apply)
}
