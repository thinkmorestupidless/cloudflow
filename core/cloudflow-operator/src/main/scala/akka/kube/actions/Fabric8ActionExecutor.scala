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

// Vendored from com.lightbend.akka:kube-actions 0.1.1 (no fabric8 API changes needed).

package akka.kube.actions

import scala.concurrent._
import scala.util.control.NonFatal

import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Fabric8ActionExecutor {
  val logger: Logger = LoggerFactory.getLogger(classOf[ActionExecutor])
}

/** Executes Kubernetes resource actions using a Fabric8 KubernetesClient.
  */
final class Fabric8ActionExecutor(val client: KubernetesClient, val executionContext: ExecutionContext)
    extends ActionExecutor {
  import Fabric8ActionExecutor._
  implicit private val _ec: ExecutionContext = executionContext

  override def execute(action: Action): Future[Action] = {
    logger.debug(action.executingMessage)
    try {
      action
        .execute(client)
        .recoverWith {
          case ex: ActionException =>
            Future.failed(ex)

          case NonFatal(cause) =>
            Future.failed(ActionException(action, cause))
        }
        .map { r =>
          logger.info(action.executedMessage)
          r
        }
    } catch {
      case NonFatal(cause) =>
        // in case `execute` throws instead of returning failed Future
        Future.failed(ActionException(action, cause))
    }
  }
}
