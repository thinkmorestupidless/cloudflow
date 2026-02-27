/*
 * Copyright (C) 2021-2026 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cli.cloudflow

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.fabric8.kubernetes.client.utils.{ KubernetesSerialization, Serialization }

object Setup {

  def init() = {
    // Configure the static Serialization.jsonMapper() so that kubernetesSerialization()
    // below (which wraps that same mapper) will include DefaultScalaModule.
    Serialization.jsonMapper().registerModule(DefaultScalaModule)
    // REMIND ME: should we turn this off?
    Serialization
      .jsonMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
  }

  /** Returns a KubernetesSerialization that includes DefaultScalaModule. Must be called after init().
    */
  def kubernetesSerialization(): KubernetesSerialization =
    new KubernetesSerialization(Serialization.jsonMapper(), false)

}
