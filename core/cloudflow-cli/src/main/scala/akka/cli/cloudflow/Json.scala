/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

object Json {

  val mapper = new ObjectMapper().registerModule(new DefaultScalaModule())

}
