/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.cli.execution

import scala.util.Try
import cloudflow.cli.{ CliException, CliLogger, ConfigurationResult, Execution }
import cloudflow.cli.commands.Configuration
import cloudflow.cli.kubeclient.KubeClient
import com.typesafe.config.ConfigFactory

final case class ConfigurationExecution(c: Configuration, client: KubeClient, logger: CliLogger)
    extends Execution[ConfigurationResult]
    with WithProtocolVersion {
  def run(): Try[ConfigurationResult] = {
    logger.info("Executing command Configuration")
    for {
      _ <- validateProtocolVersion(client, c.operatorNamespace, logger)
      res <- client.getAppInputSecret(c.cloudflowApp, c.namespace.getOrElse(c.cloudflowApp))
      config <- Try { ConfigFactory.parseString(res) }.recover { case ex =>
        throw CliException("Failed to parse the current configuration", ex)
      }
    } yield {
      ConfigurationResult(config)
    }
  }
}
