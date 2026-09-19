package helloworld

import org.apache.pekko.stream.scaladsl._
import cloudflow.pekkostream._
import cloudflow.pekkostream.scaladsl._
import cloudflow.streamlets._
import scala.concurrent.duration._

class HelloWorldShape extends PekkoStreamlet {
  val shape = StreamletShape.empty

  def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph = {
      Source
        .cycle(() => Iterator.continually("hello, world!"))
        .throttle(1, 1.second)
        .map{ msg =>
          system.log.debug(s"DEBUG $msg")
          system.log.info(s"INFO $msg")
          system.log.warning(s"WARNING $msg")
          system.log.error(s"ERROR $msg")
        }
        .to(Sink.ignore)
    }
  }
}

