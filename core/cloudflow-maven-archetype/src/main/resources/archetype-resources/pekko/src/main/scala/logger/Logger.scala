/*
 * Copyright (C) 2026 Lightbend Inc. <https://www.lightbend.com>
 */

package logger

import cloudflow.pekkostream._
import cloudflow.pekkostream.scaladsl._
import cloudflow.streamlets._
import cloudflow.streamlets.avro._
import datamodel._

class Logger extends PekkoStreamlet {
  val inlet = AvroInlet[Data]("in")
  val shape = StreamletShape.withInlets(inlet)

  override def createLogic = new RunnableGraphStreamletLogic() {
    def log(data: Data) =
      system.log.info(s"${data.id} : ${data.msg}-pekko")

    def flow =
      FlowWithCommittableContext[Data]
        .map { data ⇒
          log(data)
          data
        }

    def runnableGraph =
      sourceWithCommittableContext(inlet)
        .via(flow)
        .to(committableSink)
  }
}
