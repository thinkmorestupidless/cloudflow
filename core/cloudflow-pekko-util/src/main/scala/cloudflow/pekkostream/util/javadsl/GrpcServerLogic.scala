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

package cloudflow.pekkostream.util.javadsl

import java.util.concurrent.CompletionStage
import java.util.{ List => JList }

import org.apache.pekko.annotation.ApiMayChange
import org.apache.pekko.japi.function.Function
import org.apache.pekko.grpc.javadsl.ServiceHandler
import org.apache.pekko.http.javadsl.model.{ HttpRequest, HttpResponse }
import org.apache.pekko.http.javadsl.model.StatusCodes.OK
import org.apache.pekko.http.javadsl.server.Route
import org.apache.pekko.http.javadsl.server.Directives._
import cloudflow.pekkostream.{ PekkoStreamletContext, Server }

@ApiMayChange
abstract class GrpcServerLogic(server: Server, context: PekkoStreamletContext)
    extends HttpServerLogic(server, context) {
  def handlers(): JList[Function[HttpRequest, CompletionStage[HttpResponse]]]

  override def createRoute(): Route = {
    import scala.jdk.CollectionConverters._
    val handler = ServiceHandler.concatOrNotFound(handlers().asScala.toSeq: _*)

    concat(pathEndOrSingleSlash(() => complete(OK, "")), handle(request => handler(request)))

  }
}
