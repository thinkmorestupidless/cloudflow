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

package cloudflow.cli.execution

import java.util.UUID

import scala.util.{ Failure, Success, Try }

import cloudflow.cli.commands.{ ResetOffsets => ResetOffsetsCommand }
import cloudflow.cli.kubeclient.KubeClient
import cloudflow.cli.{ models, CliException, CliLogger, Execution, ResetOffsetsResult }
import cloudflow.crd.{ App, ResetOffsets }

/** Records a [[cloudflow.crd.ResetOffsets.Request]] on the application, for the operator to carry out.
  *
  * Only a streamlet that reads — a Pekko streamlet with inlets — has consumer groups to reset, and every one being
  * reset must be stopped: scaled to 0, with no pods left. This is checked here so the refusal is immediate and names
  * what to do; Kafka refuses, too, to reset a group that still has members, which is what holds if the status read here
  * is stale.
  */
final case class ResetOffsetsExecution(r: ResetOffsetsCommand, client: KubeClient, logger: CliLogger)
    extends Execution[ResetOffsetsResult]
    with WithProtocolVersion {

  def run(): Try[ResetOffsetsResult] = {
    logger.info("Executing command ResetOffsets")
    for {
      _ <- validateProtocolVersion(client, r.operatorNamespace, logger)
      namespace = r.namespace.getOrElse(r.cloudflowApp)
      appOpt <- client.readCloudflowApp(r.cloudflowApp, namespace)
      app <- appOpt.map(Success(_)).getOrElse(Failure(CliException(s"Application ${r.cloudflowApp} not found")))
      targets <- ResetOffsetsExecution.targets(app, r.streamlets)
      status <- client.getCloudflowAppStatus(r.cloudflowApp, namespace)
      _ <- ResetOffsetsExecution.stopped(app, targets, status)
      request = ResetOffsets.Request(UUID.randomUUID().toString, r.streamlets)
      _ <- client.updateCloudflowApp(ResetOffsetsExecution.withRequest(app, request), namespace)
    } yield ResetOffsetsResult(request.id, targets)
  }
}

object ResetOffsetsExecution {
  private val PekkoRuntime = "pekko"

  /** The streamlets whose consumer groups a request would reset: the named ones, each of which must exist and read; or,
    * when none is named, every one that reads.
    */
  def targets(app: App.Cr, named: List[String]): Try[List[String]] = {
    val spec = app.getSpec
    def reads(d: App.Deployment) =
      d.runtime == PekkoRuntime && spec.streamlets.find(_.name == d.streamletName).exists(_.descriptor.inlets.nonEmpty)

    if (named.isEmpty) {
      val all = spec.deployments.filter(reads).map(_.streamletName).toList
      if (all.isEmpty) Failure(CliException(s"Application ${spec.appId} has no Pekko streamlets with inlets to reset"))
      else Success(all)
    } else {
      val problems = named.flatMap { name =>
        spec.deployments.find(_.streamletName == name) match {
          case None => Some(s"no streamlet [$name]")
          case Some(d) if d.runtime != PekkoRuntime =>
            Some(
              s"streamlet [$name] runs on the ${d.runtime} runtime; only Pekko streamlets' consumer groups are reset")
          case Some(d) if !reads(d) => Some(s"streamlet [$name] has no inlets, so no consumer groups to reset")
          case _                    => None
        }
      }
      if (problems.isEmpty) Success(named)
      else Failure(CliException(s"Cannot reset offsets: ${problems.mkString("; ")}"))
    }
  }

  /** Every target must be scaled to 0 and have no pods left. */
  def stopped(app: App.Cr, targets: List[String], status: models.ApplicationStatus): Try[Unit] = {
    val running = targets.flatMap { name =>
      val replicas = app.getSpec.deployments.find(_.streamletName == name).flatMap(_.replicas)
      val pods = status.streamletsStatuses.find(_.name == name).map(_.podsStatuses.size).getOrElse(0)
      if (!replicas.contains(0)) Some(s"[$name] is not scaled to 0")
      else if (pods > 0) Some(s"[$name] still has $pods pod(s)")
      else None
    }
    if (running.isEmpty) Success(())
    else
      Failure(
        CliException(
          s"Cannot reset offsets while streamlets are running: ${running.mkString("; ")}. Stop them first, e.g. " +
            s"`kubectl cloudflow scale ${app.getSpec.appId} ${targets.map(t => s"$t=0").mkString(" ")}`, " +
            "and wait for their pods to go."))
  }

  def withRequest(app: App.Cr, request: ResetOffsets.Request): App.Cr = {
    val annotations = new java.util.HashMap[String, String]()
    Option(app.getMetadata.getAnnotations).foreach(a => annotations.putAll(a))
    annotations.put(ResetOffsets.RequestAnnotation, ResetOffsets.toJson(request))
    app.getMetadata.setAnnotations(annotations)
    app
  }

}
