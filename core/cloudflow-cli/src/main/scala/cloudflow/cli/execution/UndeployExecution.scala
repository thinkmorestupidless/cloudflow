/*
 * Copyright (C) 2020-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.cli.execution

import cloudflow.cli.commands.Undeploy
import cloudflow.cli.kubeclient.KubeClient
import cloudflow.cli.{ CliLogger, Execution, UndeployResult }

import scala.util.Try

final case class UndeployExecution(u: Undeploy, client: KubeClient, logger: CliLogger)
    extends Execution[UndeployResult]
    with WithProtocolVersion {
  def run(): Try[UndeployResult] = {
    logger.info("Executing command Undeploy")
    for {
      _ <- validateProtocolVersion(client, u.operatorNamespace, logger)
      _ <- client.deleteCloudflowApp(u.cloudflowApp, u.namespace.getOrElse(u.cloudflowApp))
    } yield {
      UndeployResult()
    }
  }
}
