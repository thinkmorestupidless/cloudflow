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

// Vendored from com.lightbend.akka:kube-actions 0.1.1 and updated for fabric8 6.x.
// Changes:
//   - CreateOrReplaceAction.execute: uses handler.createOrReplace() instead of
//     handler.applicable().createOrReplace() (Applicable removed in fabric8 6.x).
//   - UpdateStatusAction.execute: uses client.resources(Class<T>) instead of
//     client.customResources(crdContext,...) (removed in fabric8 6.x).

package akka.kube.actions

import scala.collection.immutable
import scala.concurrent._
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Try

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.KubernetesResourceList
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.Gettable
import io.fabric8.kubernetes.client.dsl.Listable
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.utils.Serialization
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Captures an action to create, delete or update a Kubernetes resource.
  */
trait Action {

  /** Executes the action using a KubernetesClient. Returns the action that was executed
    */
  def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[Action]

  /*
   * The name of the action
   */
  def actionName: String = Action.simpleName(this)

  /*
   * The message to log when the action starts to execute.
   */
  def executingMessage: String = s"Executing [$actionName]"

  /*
   * The message to log when the action has executed.
   */
  def executedMessage: String = s"Executed [$actionName]"

  /*
   * debugging info
   */
  val errorMessageExtraInfo: String
}

/** Creates actions.
  */
object Action {
  val log: Logger = LoggerFactory.getLogger(classOf[Action])

  private def simpleName(a: AnyRef): String = {
    val n = a.getClass.getName
    val i = n.lastIndexOf('.')
    n.substring(i + 1)
  }

  private def defaultResourceAdapter[T <: HasMetadata] = DefaultResourceAdapter[T]()

  // Type inequality used to make sure you can't provide a custom resource to Action methods where 'normal' resources are expected,
  // since this would blow up in runtime: CustomResource extends HasMetadata
  trait <:!<[A, B]

  implicit def nsub[A, B]: A <:!< B = new <:!<[A, B] {}
  implicit def use_Action_Cr_ForCustomResource_Instead1[A, B >: A]: A <:!< B = sys.error("Unexpected call")
  implicit def use_Action_Cr_ForCustomResource_Instead2[A, B >: A]: A <:!< B = sys.error("Unexpected call")

  def fullResourceName(namespace: Option[String], resourceName: String): String =
    s"${namespace.map(_ + "/").getOrElse("")}$resourceName"

  /** Creates a [[CreateOrReplaceAction]].
    */
  def createOrReplace[T <: HasMetadata](resource: T)(implicit
      ct: ClassTag[T],
      ev: T <:!< CustomResource[T, _],
      lineNumber: sourcecode.Line,
      file: sourcecode.File): CreateOrReplaceAction[T] = {
    val _ = ev
    CreateOrReplaceAction(defaultResourceAdapter[T], resource)
  }

  /** Creates a [[DeleteAction]] to delete resource by name and namespace.
    */
  def delete[T <: HasMetadata](resourceName: String, namespace: String)(implicit
      ct: ClassTag[T],
      ev: T <:!< CustomResource[T, _],
      lineNumber: sourcecode.Line,
      file: sourcecode.File): DeleteAction[T] = {
    val _ = ev
    DeleteAction(defaultResourceAdapter[T], resourceName, Some(namespace))
  }

  /** Creates a [[DeleteAction]] to delete a non-namespaced resource.
    */
  def delete[T <: HasMetadata](resourceName: String)(implicit
      ct: ClassTag[T],
      ev: T <:!< CustomResource[T, _],
      lineNumber: sourcecode.Line,
      file: sourcecode.File): DeleteAction[T] = {
    val _ = ev
    DeleteAction(defaultResourceAdapter[T], resourceName, None)
  }

  /** Creates a [[DeleteAction]] to delete the specified resource.
    */
  def delete[T <: HasMetadata](resource: T)(implicit
      ct: ClassTag[T],
      ev: T <:!< CustomResource[T, _],
      lineNumber: sourcecode.Line,
      file: sourcecode.File): DeleteAction[T] = {
    val _ = ev
    DeleteAction(defaultResourceAdapter[T], resource.getMetadata.getName, Option(resource.getMetadata.getNamespace))
  }

  /** Creates a [[CompositeAction]]. A single action that encapsulates other actions.
    */
  def composite[T <: HasMetadata](actions: immutable.Iterable[Action]): CompositeAction[T] =
    CompositeAction(actions)

  def get[T <: HasMetadata](resourceName: String, namespace: String)(fAction: Option[T] => Action)(implicit
      ct: ClassTag[T],
      ev: T <:!< CustomResource[T, _],
      lineNumber: sourcecode.Line,
      file: sourcecode.File): GetAction[T] = {
    val _ = ev
    GetAction(defaultResourceAdapter[T], resourceName, Some(namespace), fAction)
  }

  def get[T <: HasMetadata](resourceName: String)(fAction: Option[T] => Action)(implicit
      ct: ClassTag[T],
      ev: T <:!< CustomResource[T, _],
      lineNumber: sourcecode.Line,
      file: sourcecode.File): GetAction[T] = {
    val _ = ev
    GetAction(defaultResourceAdapter[T], resourceName, None, fAction)
  }

  /** Execute any operation, create an action from the result of the operation.
    */
  def operation[T <: HasMetadata, L <: KubernetesResourceList[T], OpResult](
      getOperation: KubernetesClient => MixedOperation[T, L, Resource[T]],
      executeOperation: MixedOperation[T, L, Resource[T]] => OpResult,
      createAction: OpResult => Action)(implicit lineNumber: sourcecode.Line, file: sourcecode.File): Action = {
    OperatorAction(getOperation, executeOperation, createAction)
  }

  /** list resources, create an action from the result of listing the resource.
    */
  def list[T <: HasMetadata, L <: KubernetesResourceList[T]](
      getListable: KubernetesClient => Listable[L],
      getList: Listable[L] => L,
      createAction: L => Action): Action = {
    ListAction[T, L](getListable, getList, createAction)
  }

  /** list resources as vector of items, create an action from the result of listing the resource.
    */
  def listItems[T <: HasMetadata, L <: KubernetesResourceList[T]](
      getListable: KubernetesClient => Listable[L],
      getList: Listable[L] => L = (l: Listable[L]) => l.list())(
      createAction: Vector[T] => Action)(implicit lineNumber: sourcecode.Line, file: sourcecode.File): Action = {
    ListItemsAction[T, L](getListable, getList, createAction)
  }

  /** Creates actions for custom resources.
    */
  object Cr {
    def get[T <: HasMetadata](resourceName: String, namespace: String)(fAction: Option[T] => Action)(implicit
        ct: ClassTag[T],
        handler: ResourceAdapter[T],
        lineNumber: sourcecode.Line,
        file: sourcecode.File): GetAction[T] =
      GetAction(handler, resourceName, Some(namespace), fAction)

    def get[T <: HasMetadata](resourceName: String)(fAction: Option[T] => Action)(implicit
        ct: ClassTag[T],
        handler: ResourceAdapter[T],
        lineNumber: sourcecode.Line,
        file: sourcecode.File): GetAction[T] =
      GetAction(handler, resourceName, None, fAction)

    def listItems[T <: CustomResource[_, _], L <: KubernetesResourceList[T]](
        getListable: MixedOperation[T, L, _] => Listable[L],
        getList: Listable[L] => L = (l: Listable[L]) => l.list())(createAction: Vector[T] => Action)(implicit
        handler: CustomResourceAdapter[T, L],
        lineNumber: sourcecode.Line,
        file: sourcecode.File): Action = {
      Action.listItems[T, L](client => getListable(handler.customResources(client)), getList)(createAction)
    }

    def operation[T <: CustomResource[_, _], L <: KubernetesResourceList[T], OpResult](
        executeOperation: MixedOperation[T, L, Resource[T]] => OpResult)(createAction: OpResult => Action)(implicit
        handler: CustomResourceAdapter[T, L],
        lineNumber: sourcecode.Line,
        file: sourcecode.File): Action = {
      OperatorAction(client => handler.customResources(client), executeOperation, createAction)
    }

    /** Creates a [[DeleteAction]] for a Namespaced CustomResource.
      */
    def delete[T <: CustomResource[_, _]](resourceName: String, namespace: String)(implicit
        ct: ClassTag[T],
        handler: ResourceAdapter[T],
        lineNumber: sourcecode.Line,
        file: sourcecode.File): DeleteAction[T] = {
      DeleteAction(handler, resourceName, Some(namespace))
    }

    /** Creates a [[DeleteAction]] for a CustomResource.
      */
    def delete[T <: CustomResource[_, _]](resourceName: String)(implicit
        ct: ClassTag[T],
        handler: ResourceAdapter[T],
        lineNumber: sourcecode.Line,
        file: sourcecode.File): DeleteAction[T] = {
      DeleteAction(handler, resourceName, None)
    }

    /** Creates a [[DeleteAction]] to delete the specified CustomResource.
      */
    def delete[T <: CustomResource[_, _]](resource: T)(implicit
        ct: ClassTag[T],
        handler: ResourceAdapter[T],
        lineNumber: sourcecode.Line,
        file: sourcecode.File): DeleteAction[T] = {
      DeleteAction(handler, resource.getMetadata.getName, Option(resource.getMetadata.getNamespace))
    }

    /** Creates a [[CreateOrReplaceAction]] for a CustomResource.
      */
    def createOrReplace[T <: HasMetadata](resource: T)(implicit
        ct: ClassTag[T],
        handler: ResourceAdapter[T],
        lineNumber: sourcecode.Line,
        file: sourcecode.File): CreateOrReplaceAction[T] = {
      CreateOrReplaceAction(handler, resource)
    }

    /** Updates the status of a custom resource if it is found, otherwise returns the resource unchanged.
      */
    def updateStatus[T <: CustomResource[_, _], L <: KubernetesResourceList[T]](resource: T)(implicit
        ct: ClassTag[T],
        w: CustomResourceAdapter[T, L],
        lineNumber: sourcecode.Line,
        file: sourcecode.File): UpdateStatusAction[T, L] = {
      UpdateStatusAction(resource)
    }
  }

  /** Creates a no-operation action, which effectively does nothing ([[NoopAction]]).
    */
  val noop = NoopAction

  /** Creates an action that can be used to return a value after the action has executed.
    */
  def result[T](result: T) = Result(result)

  /** Creates an action that executes `action` and returns a value after the action has executed.
    */
  def withResult[T](action: Action, result: T)(implicit lineNumber: sourcecode.Line, file: sourcecode.File) =
    With(action, result)

  /** Creates an action from a result of another action.
    */
  def fromResult[T](action: Result[T])(
      nextAction: T => Action)(implicit lineNumber: sourcecode.Line, file: sourcecode.File): Action =
    FromResult(action, nextAction)

  /** Creates an action from optional results of other actions.
    */
  def fromResults[T](actions: Vector[ResultAction[Option[T]]])(
      nextAction: Vector[Option[T]] => Action)(implicit lineNumber: sourcecode.Line, file: sourcecode.File): Action =
    FromResults(actions, nextAction)

  /** No Operation action.
    */
  case object NoopAction extends ResourceAction[Nothing] {
    def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[ResourceAction[Nothing]] =
      Future.successful(this)

    private val lineNumber = implicitly[sourcecode.Line]
    private val file = implicitly[sourcecode.File]
    val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
  }

  sealed trait ResultAction[T] extends Action {
    def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[ResultAction[T]]
    def value: T
  }

  final case class GetResult[T <: HasMetadata](getGettable: KubernetesClient => Gettable[T])(implicit
      lineNumber: sourcecode.Line,
      file: sourcecode.File)
      extends ResultAction[Option[T]] {
    def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[Result[Option[T]]] = {
      Future {
        Result(Option(getGettable(client).get()))
      }
    }

    override def value: Option[T] = None
    val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
  }

  final case class Result[T](value: T)(implicit lineNumber: sourcecode.Line, file: sourcecode.File)
      extends ResultAction[T] {
    def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[Result[T]] =
      Future.successful(this)

    val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
  }

  final case class With[T](action: Action, value: T)(implicit lineNumber: sourcecode.Line, file: sourcecode.File)
      extends ResultAction[T] {
    override def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[With[T]] = {
      action.execute(client).map(action => With(action, this.value))
    }

    val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
  }

  final case class FromResult[T](action: Result[T], nextAction: T => Action)(implicit
      lineNumber: sourcecode.Line,
      file: sourcecode.File)
      extends Action {
    def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[Action] = {
      action.execute(client).flatMap { resultAction =>
        nextAction(resultAction.value).execute(client)
      }
    }

    val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
  }

  final case class FromResults[T](actions: Vector[ResultAction[Option[T]]], nextAction: Vector[Option[T]] => Action)(
      implicit
      lineNumber: sourcecode.Line,
      file: sourcecode.File)
      extends Action {
    def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[Action] = {
      actions
        .foldLeft(Future.successful(Vector.empty[ResultAction[Option[T]]])) { (acc, action) =>
          acc.flatMap { v =>
            action.execute(client).map { (res: ResultAction[Option[T]]) =>
              v :+ res
            }
          }
        }
        .flatMap(results => nextAction(results.map(_.value)).execute(client))
    }
    val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
  }
}

object ResourceAction {
  val log: Logger = LoggerFactory.getLogger(classOf[ResourceAction[_]])
  val ConflictCode: Int = 409
}

abstract class ResourceAction[+T <: HasMetadata] extends Action {

  override def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[ResourceAction[T]]
}

trait GetByNameAction[T <: HasMetadata] {
  def resource(resourceName: String, namespace: Option[String])(implicit ct: ClassTag[T]): T = {
    val default = {
      Serialization
        .jsonMapper()
        .readValue("{}", ct.runtimeClass)
        .asInstanceOf[T]
    }

    val metadata = new ObjectMetaBuilder()
      .withName(resourceName)
    val metadataWithNamespace = namespace
      .map { ns =>
        metadata
          .withNamespace(ns)
          .build()
      }
      .getOrElse(metadata.build())
    default.setMetadata(metadataWithNamespace)
    default
  }
}

abstract class SingleResourceAction[T <: HasMetadata] extends ResourceAction[T] {

  /** The resource that is applied
    */
  def resource: T

  override def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[SingleResourceAction[T]]

  /** The name of the resource that this action is applied to
    */
  def resourceName: String = resource.getMetadata.getName
  def namespace: Option[String] = Option(resource.getMetadata.getNamespace)

  override def executingMessage =
    s"Executing [$actionName] action for ${resource.getKind} [${Action.fullResourceName(namespace, resourceName)}]"
  override def executedMessage =
    s"Executed [$actionName] action for ${resource.getKind} [${Action.fullResourceName(namespace, resourceName)}]"
}

/** Captures create or replace of the resource. This action does not fail if the resource already exists. If the
  * resource already exists, it will be replaced.
  */
final case class CreateOrReplaceAction[T <: HasMetadata](handler: ResourceAdapter[T], resource: T)(implicit
    ct: ClassTag[T],
    lineNumber: sourcecode.Line,
    file: sourcecode.File)
    extends SingleResourceAction[T] {

  /** Creates the resources if it does not exist. If it does exist it updates the resource as required. Updated for
    * fabric8 6.x: uses handler.createOrReplace() instead of handler.applicable().
    */
  def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[CreateOrReplaceAction[T]] =
    for {
      result <- Future { handler.createOrReplace(client, resource) }
    } yield CreateOrReplaceAction(handler, result)

  val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
}

/** Captures deletion of the resource.
  */
final case class DeleteAction[T <: HasMetadata](
    handler: ResourceAdapter[T],
    resourceName: String,
    namespace: Option[String] = None)(implicit ct: ClassTag[T], lineNumber: sourcecode.Line, file: sourcecode.File)
    extends ResourceAction[T]
    with GetByNameAction[T] {

  /*
   * Deletes the resource.
   */
  def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[DeleteAction[T]] =
    Future {
      val res = resource(resourceName, namespace)
      Try {
        handler
          .foregroundDeletable(client, res)
          .delete()
      }.recoverWith { case e =>
        Failure(
          ActionException(
            this,
            s"Could not delete ${res.getKind} ${Action.fullResourceName(namespace, resourceName)}",
            e))
      }
      this
    }

  val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
}

final case class CompositeAction[T <: HasMetadata](actions: immutable.Iterable[Action])(implicit
    lineNumber: sourcecode.Line,
    file: sourcecode.File)
    extends Action {
  require(actions.nonEmpty)
  override def executingMessage =
    s"Composite action executing: [${actions.map(_.actionName).mkString(", ")}]"
  override def executedMessage =
    s"Composite action executed: ${actions.map(_.actionName).mkString(", ")}"

  /** Executes all actions
    */
  override def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[CompositeAction[T]] = {
    actions
      .foldLeft(Future.successful(Vector.empty[Action])) { (acc, action) =>
        acc.flatMap { v =>
          action.execute(client).map { (res: Action) =>
            v :+ res
          }
        }
      }
      .map(results => CompositeAction(results))
  }

  val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
}

/** Gets a resource and creates an action from it.
  */
final case class GetAction[T <: HasMetadata](
    handler: ResourceAdapter[T],
    resourceName: String,
    namespace: Option[String] = None,
    getAction: Option[T] => Action)(implicit ct: ClassTag[T], lineNumber: sourcecode.Line, file: sourcecode.File)
    extends Action
    with GetByNameAction[T] {

  override def executingMessage =
    s"Get [${Action.fullResourceName(namespace, resourceName)}] to next action"
  override def executedMessage =
    s"Executed get [${Action.fullResourceName(namespace, resourceName)}] to next action"

  def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[Action] = {
    Future {
      Option(
        handler
          .gettable(client, resource(resourceName, namespace))
          .get())
    }.flatMap { res =>
      if (res.isEmpty)
        Action.log.debug("[{}] not found", Action.fullResourceName(namespace, resourceName))
      getAction(res).execute(client)
    }
  }

  val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
}

/** Lists a resource and creates an action from it.
  */
final case class ListAction[T <: HasMetadata, L <: KubernetesResourceList[T]](
    getListable: KubernetesClient => Listable[L],
    getList: Listable[L] => L,
    createAction: L => Action)(implicit lineNumber: sourcecode.Line, file: sourcecode.File)
    extends Action {

  override def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[Action] = {
    Future {
      createAction(getList(getListable(client)))
    }.flatMap(_.execute(client))
  }

  val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
}

/** Lists a resource as vector of items and creates an action from the result of listing the resource.
  */
final case class ListItemsAction[T <: HasMetadata, L <: KubernetesResourceList[T]](
    getListable: KubernetesClient => Listable[L],
    getList: Listable[L] => L = (l: Listable[L]) => l.list(),
    createAction: Vector[T] => Action)(implicit lineNumber: sourcecode.Line, file: sourcecode.File)
    extends Action {
  import akka.kube.ccompat
  def asScala(l: L): Vector[T] = ccompat.asScala(l.getItems)
  override def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[Action] = {
    Future {
      createAction(asScala(getList(getListable(client))))
    }.flatMap(_.execute(client))
  }

  val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
}

/** Updates the status of a custom resource if it is found, otherwise returns the resource unchanged. Updated for
  * fabric8 6.x: uses client.resources(Class<T>) instead of client.customResources(crdContext,...) which was removed in
  * fabric8 6.x.
  */
final case class UpdateStatusAction[T <: CustomResource[_, _], L <: KubernetesResourceList[T]](resource: T)(implicit
    ct: ClassTag[T],
    w: CustomResourceAdapter[T, L],
    lineNumber: sourcecode.Line,
    file: sourcecode.File)
    extends SingleResourceAction[T] {

  def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[UpdateStatusAction[T, L]] = {
    for {
      result <- Future {
        client
          .resources(ct.runtimeClass.asInstanceOf[Class[T]])
          .inNamespace(resource.getMetadata.getNamespace)
          .withName(resource.getMetadata.getName)
          .updateStatus(resource)
      }
    } yield UpdateStatusAction(result)
  }

  val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
}

final case class OperatorAction[T <: HasMetadata, L <: KubernetesResourceList[T], R <: Resource[T], OpResult](
    getOperation: KubernetesClient => MixedOperation[T, L, R],
    executeOperation: MixedOperation[T, L, R] => OpResult,
    createAction: OpResult => Action)(implicit lineNumber: sourcecode.Line, file: sourcecode.File)
    extends Action {

  def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[Action] = {
    Future {
      createAction(executeOperation(getOperation(client)))
    }.flatMap(_.execute(client))
  }

  val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"
}
