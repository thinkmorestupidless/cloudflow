package helloworld

import org.apache.pekko.stream.scaladsl._
import cloudflow.pekkostream._
import cloudflow.pekkostream.scaladsl._
import cloudflow.streamlets._
import scala.util.Try
import scala.concurrent.duration._

class HelloWorldShape extends PekkoStreamlet {
  val shape = StreamletShape.empty

  def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph = {
      Source
        .cycle(() => Iterator.continually("hello world!"))
        .throttle(1, 1.second)
        .map(println)
        .to(Sink.ignore)
    }
  }
}
