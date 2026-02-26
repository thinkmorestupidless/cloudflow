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

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.collection.immutable

/**
 * Executes Kubernetes resource actions.
 * Any non-fatal exception in execute should result in a failure containing an [[ActionException]]
 */
trait ActionExecutor {
  def executionContext: ExecutionContext

  /**
   * Executes the action. Returns the action as executed, containing the object as it was returned by the action.
   * In the case of deletion, the original resource is returned.
   */
  def execute(action: Action): Future[Action]

  /**
   * Executes the actions. Returns the actions as executed, containing the objects as they were returned by the actions.
   * In the case of deletion, the original resource is returned.
   */
  def execute(actions: immutable.Seq[Action]): Future[immutable.Seq[Action]] = {
    implicit val ec: ExecutionContext = executionContext
    actions
      .foldLeft(Future.successful(Vector.empty[Action])) { (acc, action) =>
        acc.flatMap { v =>
          execute(action).flatMap { res: Action =>
            Future.successful(v :+ res)
          }
        }
      }
  }
}

object ActionException {
  def apply(action: Action, cause: Throwable): ActionException =
    new ActionException(
      action,
      s"Action [${action.actionName}] failed: ${cause.getMessage}, ${action.errorMessageExtraInfo}",
      cause)

  def apply(action: Action, msg: String): ActionException =
    new ActionException(action, s"Action [${action.actionName}] failed: ${msg}, ${action.errorMessageExtraInfo}", null)
}

/**
 * Exception thrown when the action failed to make the appropriate change(s).
 */
final case class ActionException(action: Action, msg: String, cause: Throwable) extends RuntimeException(msg, cause)
