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

package cloudflow.operator.action

import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

import cloudflow.crd.{ App, ResetOffsets }
import cloudflow.kube.actions.Action
import cloudflow.operator.action.runner.PekkoRunner
import io.fabric8.kubernetes.api.model.ObjectReference
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{ MixedOperation, Resource }
import org.apache.kafka.clients.admin.Admin
import org.slf4j.LoggerFactory

/** Carries out a [[cloudflow.crd.ResetOffsets.Request]]: resets the consumer group of every inlet of the requested
  * streamlets to the earliest offsets of the topic it reads, over the same Kafka connection that streamlet uses, then
  * records the request as done. Each group's outcome is reported as a Kubernetes event on the application; a group that
  * cannot be reset (its streamlet is still running, its Kafka configuration is missing) is a warning, not an
  * application error, and does not stop the others.
  */
object ResetOffsetsActions {
  private val log = LoggerFactory.getLogger(getClass)

  val ResetReason = "ResetOffsets"
  val ResetFailedReason = "ResetOffsetsFailed"

  /** One inlet's consumer group, and the topic it reads. */
  final case class Target(
      streamlet: String,
      inlet: String,
      groupId: String,
      portMapping: App.PortMapping,
      secretName: String)

  /** The inlets of the requested streamlets. Only the Pekko runtime's streamlets are included: the consumer group
    * naming is its own.
    */
  def targets(app: App.Cr, request: ResetOffsets.Request): Seq[Target] = {
    val spec = app.getSpec
    for {
      deployment <- spec.deployments
      if deployment.runtime == PekkoRunner.Runtime && request.includes(deployment.streamletName)
      streamlet <- spec.streamlets.find(_.name == deployment.streamletName).toList
      inlet <- streamlet.descriptor.inlets
      portMapping <- deployment.portMappings.get(inlet.name).toList
    } yield Target(
      deployment.streamletName,
      inlet.name,
      ResetOffsets.groupId(spec.appId, deployment.streamletName, inlet.name),
      portMapping,
      deployment.secretName)
  }

  def apply(
      app: App.Cr,
      request: ResetOffsets.Request,
      podName: String,
      namedClustersNamespace: String,
      cause: ObjectReference): Seq[Action] = {
    def event(reason: String, message: String, warning: Boolean = false): Action =
      EventActions.createEvent(
        app = app,
        podName = podName,
        reason = reason,
        message = message,
        `type` = if (warning) EventActions.EventType.Warning else EventActions.EventType.Normal,
        objectReference = cause)

    val unknownStreamlets = request.streamlets.filterNot(s => app.getSpec.deployments.exists(_.streamletName == s))
    val unknown = unknownStreamlets.map(s =>
      event(ResetFailedReason, s"Cannot reset offsets of streamlet [$s]: no such streamlet", warning = true))

    val resets = targets(app, request).map { target =>
      val topic = TopicActions.TopicInfo(TopicActions.portMappingToTopic(target.portMapping))
      def failed(reason: String) =
        event(
          ResetFailedReason,
          s"Could not reset consumer group [${target.groupId}] on topic [${topic.name}]: $reason",
          warning = true)

      TopicActions.withKafkaConnection(Some(target.secretName), topic, app, namedClustersNamespace)(
        use = connection =>
          connection.bootstrapServers match {
            case Some(bootstrapServers) =>
              new ResetGroupAction(
                TopicActions.KafkaAdmins.getOrCreate(bootstrapServers, connection.brokerConfig),
                target.groupId,
                topic.name,
                partitions =>
                  event(
                    ResetReason,
                    s"Reset consumer group [${target.groupId}] on topic [${topic.name}] to the earliest offsets " +
                      s"($partitions partitions)"),
                error => failed(error.getMessage))
            case None => failed("no bootstrap servers in its Kafka configuration")
          },
        onMissing = failed)
    }

    log.info(s"Resetting offsets for ${app.getSpec.appId}, request ${request.id}: ${resets.size} consumer group(s)")
    unknown ++ resets :+ markDone(app, request.id)
  }

  /** Runs the reset, then the event reporting its outcome. Executed in order with the request's other actions. */
  private final class ResetGroupAction(
      admin: Admin,
      groupId: String,
      topic: String,
      succeeded: Int => Action,
      failed: Throwable => Action)(implicit val file: sourcecode.File, val lineNumber: sourcecode.Line)
      extends Action {
    val errorMessageExtraInfo = s"created on: ${file.value}:${lineNumber.value}"

    def execute(client: KubernetesClient)(implicit ec: ExecutionContext): Future[Action] =
      ConsumerGroupReset
        .toEarliest(admin, groupId, topic)
        .map(succeeded)
        .recover { case error =>
          log.warn(s"Could not reset consumer group [$groupId] on topic [$topic]", error)
          failed(error)
        }
        .flatMap(_.execute(client))
  }

  /** Records the request as carried out, so it is not carried out again — when the operator restarts, for instance. */
  def markDone(app: App.Cr, requestId: String): Action =
    Action.operation[App.Cr, App.List, Try[Unit]](
      (client: KubernetesClient) =>
        client.resources(classOf[App.Cr]).asInstanceOf[MixedOperation[App.Cr, App.List, Resource[App.Cr]]],
      (crs: MixedOperation[App.Cr, App.List, Resource[App.Cr]]) =>
        Try {
          crs
            .inNamespace(app.namespace)
            .withName(app.name)
            .edit { (current: App.Cr) =>
              val annotations = new java.util.HashMap[String, String]()
              Option(current.getMetadata.getAnnotations).foreach(annotations.putAll)
              annotations.put(ResetOffsets.DoneAnnotation, requestId)
              current.getMetadata.setAnnotations(annotations)
              current
            }
          ()
        },
      {
        case Success(_) => Action.noop
        case Failure(error) =>
          log.error(s"Could not record reset-offsets request $requestId as done on ${app.name}", error)
          Action.noop
      })
}
