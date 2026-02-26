/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.operator.action

import com.fasterxml.jackson.databind.JsonNode
import com.typesafe.config.{ Config, ConfigFactory }
import io.fabric8.kubernetes.client.utils.Serialization

object JsonConfig {

  def apply(json: JsonNode): Config = {
    ConfigFactory.parseString(Serialization.jsonMapper().writeValueAsString(json))
  }

}
