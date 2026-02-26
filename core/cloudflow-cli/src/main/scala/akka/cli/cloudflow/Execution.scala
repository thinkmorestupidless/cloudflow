/*
 * Copyright (C) 2020-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import scala.util.Try

trait Execution[T] {
  def run(): Try[T]
}
