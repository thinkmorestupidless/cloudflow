/*
 * Copyright (C) 2016-2026 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.pekkostream.util.scaladsl

import scala.collection.immutable
import scala.concurrent.Future

import org.apache.pekko.annotation.ApiMayChange
import org.apache.pekko.grpc.scaladsl.ServiceHandler
import org.apache.pekko.http.scaladsl.model.{ HttpRequest, HttpResponse }
import org.apache.pekko.http.scaladsl.model.StatusCodes.OK
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.Directives._
import cloudflow.pekkostream.{ PekkoStreamletContext, Server }

@ApiMayChange
abstract class GrpcServerLogic(server: Server)(implicit context: PekkoStreamletContext)
    extends HttpServerLogic(server) {
  def handlers(): immutable.Seq[PartialFunction[HttpRequest, Future[HttpResponse]]]

  override def route(): Route =
    concat(
      pathEndOrSingleSlash {
        complete(OK, "")
      },
      handle(ServiceHandler.concatOrNotFound(handlers(): _*)))

}
