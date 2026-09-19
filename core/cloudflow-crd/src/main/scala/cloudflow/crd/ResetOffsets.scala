/*
 * Copyright (C) 2016-2026 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.crd

import scala.util.Try

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule

/** A request to reset the consumer groups of an application's streamlets to the earliest offsets of the topics they
  * read — so that a pipeline reprocesses its inputs from the start, which is how a projection it feeds (a graph) is
  * rebuilt.
  *
  * The CLI records a request as an annotation on the `CloudflowApplication`; the operator, which already holds the
  * Kafka connection each streamlet uses, carries it out and records the request's id as done on a second annotation. So
  * a request is carried out once, not again when the operator restarts, and a new request is a new id. Both ends share
  * this definition so they cannot disagree about the format.
  */
object ResetOffsets {
  val RequestAnnotation = "cloudflow.lightbend.com/reset-offsets"
  val DoneAnnotation = "cloudflow.lightbend.com/reset-offsets-done"

  /** @param streamlets
    *   the streamlets whose inlets to reset; empty means every streamlet of the application.
    */
  final case class Request(id: String, streamlets: List[String] = Nil) {
    def includes(streamlet: String): Boolean = streamlets.isEmpty || streamlets.contains(streamlet)
  }

  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)

  def toJson(request: Request): String = mapper.writeValueAsString(request)

  def fromJson(json: String): Try[Request] = Try(mapper.readValue(json, classOf[Request]))

  /** The request recorded on the application, if any and if it parses. */
  def request(app: App.Cr): Option[Request] =
    annotation(app, RequestAnnotation).flatMap(fromJson(_).toOption)

  /** The id of the last request the operator carried out, if any. */
  def done(app: App.Cr): Option[String] = annotation(app, DoneAnnotation)

  /** The recorded request, unless it has already been carried out. */
  def pending(app: App.Cr): Option[Request] = request(app).filterNot(r => done(app).contains(r.id))

  /** The consumer group an inlet of a streamlet reads with. This must match `cloudflow.streamlets.Topic.groupId`, which
    * the runtime uses, for an inlet without a unique group id; an inlet with one gets a fresh group on every start, so
    * it always starts from the beginning and there is nothing to reset.
    */
  def groupId(appId: String, streamlet: String, inlet: String): String = s"$appId.$streamlet.$inlet"

  private def annotation(app: App.Cr, key: String): Option[String] =
    Option(app.getMetadata).flatMap(m => Option(m.getAnnotations)).flatMap(a => Option(a.get(key)))
}
