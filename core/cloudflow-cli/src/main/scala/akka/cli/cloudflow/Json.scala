/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import com.fasterxml.jackson.databind.{ ObjectMapper, PropertyNamingStrategies }
import com.fasterxml.jackson.module.scala.DefaultScalaModule

object Json {

  // In Scala 3, DefaultScalaModule discovers properties by Scala field names (camelCase).
  // The App.* CRD classes use snake_case JSON keys (e.g. "library_version" for libraryVersion).
  // Setting SNAKE_CASE naming strategy bridges this gap so deserialization works correctly.
  val mapper = new ObjectMapper()
    .registerModule(new DefaultScalaModule())
    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

}
