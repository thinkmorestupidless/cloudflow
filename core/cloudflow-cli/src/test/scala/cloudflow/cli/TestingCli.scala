/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.cli

import java.io.File

import cloudflow.cli.commands.Command
import cloudflow.cli.kubeclient.KubeClient

class TestingCli(kubeClientFactory: (Option[File], CliLogger) => KubeClient)
    extends Cli(None, kubeClientFactory)(new CliLogger(None)) {

  def transform[T](cmd: Command[T], res: T): T = res

  def handleError[T](cmd: Command[T], ex: Throwable): Unit = ()
}
