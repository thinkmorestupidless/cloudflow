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

package cloudflow.operator.action

import scala.jdk.CollectionConverters._

import cloudflow.blueprint.BlueprintBuilder._
import cloudflow.blueprint.{ Topic => BTopic, _ }
import cloudflow.crd.{ App, ResetOffsets }
import cloudflow.operator.event._
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ResetOffsetsSpec extends AnyWordSpec with Matchers with OptionValues {
  case class Foo(name: String)
  case class Bar(name: String)

  private val ingress = randomStreamlet().asIngress[Foo]
  private val processor = randomStreamlet().asProcessor[Foo, Bar]
  private val egress = randomStreamlet().asEgress[Bar]
  private val ingressRef = ingress.ref("ingress")
  private val processorRef = processor.ref("processor")
  private val egressRef = egress.ref("egress")
  private val spec = CloudflowApplicationSpecBuilder.create(
    "shop",
    "1",
    "image",
    Blueprint()
      .define(Vector(ingress, processor, egress))
      .use(ingressRef)
      .use(processorRef)
      .use(egressRef)
      .connect(BTopic("foos"), ingressRef.out, processorRef.in)
      .connect(BTopic("bars"), processorRef.out, egressRef.in)
      .verified
      .value,
    Map.empty)

  private def app(
      resourceVersion: String,
      request: Option[ResetOffsets.Request] = None,
      done: Option[String] = None,
      appSpec: App.Spec = spec): App.Cr = {
    val annotations =
      (request.map(r => ResetOffsets.RequestAnnotation -> ResetOffsets.toJson(r)) ++
        done.map(ResetOffsets.DoneAnnotation -> _)).toMap
    App.Cr(
      _spec = appSpec,
      _metadata = new ObjectMetaBuilder()
        .withName("shop")
        .withNamespace("shop")
        .withUid("uid")
        .withResourceVersion(resourceVersion)
        .withAnnotations(annotations.asJava)
        .build())
  }

  "A reset-offsets request" should {
    "round-trip through its annotation" in {
      val request = ResetOffsets.Request("r1", List("processor"))
      ResetOffsets.request(app("1", Some(request))) mustBe Some(request)
    }

    "be pending until its id is recorded as done, and a new id be pending again" in {
      val request = ResetOffsets.Request("r1")
      ResetOffsets.pending(app("1", Some(request))) mustBe Some(request)
      ResetOffsets.pending(app("2", Some(request), done = Some("r1"))) mustBe None
      ResetOffsets.pending(app("3", Some(ResetOffsets.Request("r2")), done = Some("r1"))).map(_.id) mustBe Some("r2")
    }

    "name the same consumer group the runtime reads with" in {
      ResetOffsets.groupId("shop", "processor", "in") mustBe "shop.processor.in"
    }
  }

  "The operator" should {
    def observe(
        apps: Map[String, WatchEvent[App.Cr]],
        cr: App.Cr,
        eventType: WatchEventType = WatchEventType.UPDATION) =
      AppEvent.toDeployEvent(apps, WatchEvent(cr, eventType))
    def resets(events: List[AppEvent]) = events.collect { case e: ResetOffsetsEvent => e.request.id }

    "raise a reset once per request, not again on the status updates that follow it, nor once it is done" in {
      val (deployed, first) = observe(Map.empty, app("1"), WatchEventType.ADDITION)
      resets(first) mustBe empty

      val requested = app("2", Some(ResetOffsets.Request("r1")))
      val (afterRequest, second) = observe(deployed, requested)
      resets(second) mustBe List("r1")
      second.collect { case d: DeployEvent => d } mustBe empty // the spec did not change

      val (afterStatus, third) = observe(afterRequest, app("3", Some(ResetOffsets.Request("r1"))))
      resets(third) mustBe empty

      val (_, fourth) = observe(afterStatus, app("4", Some(ResetOffsets.Request("r1")), done = Some("r1")))
      resets(fourth) mustBe empty
    }

    "carry out a request left pending when it restarts, and not one already done" in {
      resets(observe(Map.empty, app("9", Some(ResetOffsets.Request("r1"))), WatchEventType.ADDITION)._2) mustBe
      List("r1")
      resets(
        observe(
          Map.empty,
          app("9", Some(ResetOffsets.Request("r1")), done = Some("r1")),
          WatchEventType.ADDITION)._2) mustBe
      empty
    }

    "raise a new request by its new id" in {
      val (apps, _) = observe(Map.empty, app("1", Some(ResetOffsets.Request("r1")), done = Some("r1")))
      resets(observe(apps, app("2", Some(ResetOffsets.Request("r2")), done = Some("r1")))._2) mustBe List("r2")
    }
  }

  "The consumer groups a request resets" should {
    "be every inlet of every streamlet when the request names none" in {
      ResetOffsetsActions.targets(app("1"), ResetOffsets.Request("r1")).map(_.groupId) must contain theSameElementsAs
      List("shop.processor.in", "shop.egress.in")
    }

    "be only the named streamlets' inlets, reading the topics they are connected to" in {
      val targets = ResetOffsetsActions.targets(app("1"), ResetOffsets.Request("r1", List("egress")))
      targets.map(_.groupId) mustBe List("shop.egress.in")
      targets.head.portMapping.id mustBe "bars"
    }

    "leave out streamlets of another runtime, whose consumer groups are not named this way" in {
      val sparkSpec = spec.copy(deployments = spec.deployments.map { d =>
        if (d.streamletName == "processor") d.copy(runtime = "spark") else d
      })
      ResetOffsetsActions.targets(app("1", appSpec = sparkSpec), ResetOffsets.Request("r1")).map(_.groupId) mustBe
      List("shop.egress.in")
    }

    "each become one action, with one warning per unknown streamlet, and end with recording the request as done" in {
      val actions = ResetOffsetsActions(
        app("1"),
        ResetOffsets.Request("r1", List("processor", "egress", "nope")),
        "operator-pod",
        "cloudflow",
        Event.toObjectReference(app("1")))
      actions must have size 4 // 1 unknown streamlet + 2 groups + done
    }
  }
}
