/*
 * Copyright (C) 2020-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.cli.execution

import cloudflow.cli.commands.UpdateCredentials
import cloudflow.cli.kubeclient.KubeClient
import cloudflow.cli.{ CliLogger, Execution, UpdateCredentialsResult }

import scala.util.Try

final case class UpdateCredentialsExecution(u: UpdateCredentials, client: KubeClient, logger: CliLogger)
    extends Execution[UpdateCredentialsResult]
    with WithProtocolVersion {
  def run(): Try[UpdateCredentialsResult] = {
    logger.info("Executing command UpdateCredentials")
    for {
      _ <- validateProtocolVersion(client, u.operatorNamespace, logger)
      namespace = u.namespace.getOrElse(u.cloudflowApp)
      _ <- client.createNamespace(namespace)
      _ <- client.createImagePullSecret(
        namespace = namespace,
        dockerRegistryURL = u.dockerRegistry,
        dockerUsername = u.username,
        dockerPassword = u.password)
    } yield {
      UpdateCredentialsResult()
    }
  }
}
