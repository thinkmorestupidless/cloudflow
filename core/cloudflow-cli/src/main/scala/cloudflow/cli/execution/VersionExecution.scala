/*
 * Copyright (C) 2020-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.cli.execution

import scala.util.{ Success, Try }
import cloudflow.cli.{ Execution, VersionResult }
import cloudflow.cli.commands.Version
import buildinfo.BuildInfo

final case class VersionExecution(v: Version) extends Execution[VersionResult] {
  def run(): Try[VersionResult] = {
    Success(VersionResult(BuildInfo.version))
  }
}
