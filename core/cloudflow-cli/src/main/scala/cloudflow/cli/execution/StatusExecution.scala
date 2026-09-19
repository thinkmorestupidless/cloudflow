/*
 * Copyright (C) 2020-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.cli.execution

import scala.util.Try

import cloudflow.cli.{ CliLogger, Execution, StatusResult }
import cloudflow.cli.commands.Status
import cloudflow.cli.kubeclient.KubeClient

final case class StatusExecution(s: Status, client: KubeClient, logger: CliLogger)
    extends Execution[StatusResult]
    with WithProtocolVersion {
  def run(): Try[StatusResult] = {
    logger.info("Executing command Status")
    for {
      _ <- validateProtocolVersion(client, s.operatorNamespace, logger)
      res <- client.getCloudflowAppStatus(s.cloudflowApp, s.namespace.getOrElse(s.cloudflowApp))
    } yield {
      StatusResult(res)
    }
  }
}
