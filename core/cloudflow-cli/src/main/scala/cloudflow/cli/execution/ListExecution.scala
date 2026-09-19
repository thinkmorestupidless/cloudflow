/*
 * Copyright (C) 2020-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.cli.execution

import scala.util.Try

import cloudflow.cli.{ CliLogger, Execution, ListResult }
import cloudflow.cli.kubeclient.KubeClient
import cloudflow.cli.commands.List

final case class ListExecution(l: List, client: KubeClient, logger: CliLogger)
    extends Execution[ListResult]
    with WithProtocolVersion {
  def run(): Try[ListResult] = {
    logger.info("Executing command List")
    for {
      _ <- validateProtocolVersion(client, l.operatorNamespace, logger)
      res <- client.listCloudflowApps(l.namespace)
    } yield {
      ListResult(res)
    }
  }
}
