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

import java.time.Duration
import java.util.{ Properties, UUID }

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import cloudflow.blueprint.BlueprintBuilder._
import cloudflow.blueprint.{ Topic => BTopic, _ }
import cloudflow.crd.{ App, ResetOffsets }
import cloudflow.kube.actions.{ Action, Fabric8ActionExecutor }
import cloudflow.operator.action.runner.Base64Helper
import cloudflow.operator.event.Event
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.ConfigFactory
import io.fabric8.kubernetes.api.model.{ Event => K8sEvent, ObjectMetaBuilder, SecretBuilder }
import io.fabric8.kubernetes.client.{ KubernetesClient, KubernetesClientBuilder }
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import io.fabric8.kubernetes.client.utils.KubernetesSerialization
import org.apache.kafka.clients.admin.{ Admin, AdminClientConfig, NewTopic }
import org.apache.kafka.clients.consumer.{ ConsumerConfig, KafkaConsumer }
import org.apache.kafka.clients.producer.{ KafkaProducer, ProducerConfig, ProducerRecord }
import org.apache.kafka.common.serialization.{ StringDeserializer, StringSerializer }
import org.scalatest.{ BeforeAndAfterAll, OptionValues }
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName

/** The operator's reset, end to end short of a real cluster: the actions it produces for a request, run by its own
  * action executor against a Kubernetes API (fabric8's mock server, CRUD mode) holding the application and the
  * streamlet's secret, and against a real Kafka the secret points at.
  */
class ResetOffsetsIntegrationSpec extends AnyWordSpec with Matchers with OptionValues with BeforeAndAfterAll {
  case class Foo(name: String)
  case class Bar(name: String)

  private val kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:5.4.3"))
  private val server = new KubernetesServer(false, true)
  private lazy val admin = Admin.create(props(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG -> kafka.getBootstrapServers))
  private lazy val client: KubernetesClient = {
    val mapper = new ObjectMapper().registerModule(DefaultScalaModule).setSerializationInclusion(Include.NON_ABSENT)
    new KubernetesClientBuilder()
      .withConfig(server.getClient.getConfiguration)
      .withKubernetesSerialization(new KubernetesSerialization(mapper, true))
      .build()
  }
  private lazy val executor = new Fabric8ActionExecutor(client, ExecutionContext.global)

  override def beforeAll(): Unit = {
    kafka.start()
    server.before()
  }
  override def afterAll(): Unit = {
    admin.close()
    server.after()
    kafka.stop()
  }

  private val Namespace = "shop"
  private val Partitions = 3
  private val Records = 30

  "A reset-offsets request carried out by the operator" should {
    "reset the streamlet's consumer group through its own Kafka connection, report it, and record the request done" in {
      val topic = newTopicWithRecords()
      val app = deploy(topic)
      consumeEverythingAndCommit(topic, "shop.processor.in")
      committed("shop.processor.in").values.sum mustBe Records

      val request = ResetOffsets.Request(UUID.randomUUID().toString, List("processor"))
      run(ResetOffsetsActions(app, request, "operator-pod", "cloudflow", Event.toObjectReference(app)))

      committed("shop.processor.in") mustBe (0 until Partitions).map(_ -> 0L).toMap
      events(ResetOffsetsActions.ResetReason).exists(_.contains("[shop.processor.in]")) mustBe true
      ResetOffsets.done(storedApp()) mustBe Some(request.id)
    }

    "refuse a group whose streamlet is still reading, as a warning, and still record the request done" in {
      val topic = newTopicWithRecords()
      val app = deploy(topic)
      val consumer = subscribedConsumer(topic, "shop.processor.in")
      try {
        readAll(consumer)
        consumer.commitSync()

        val request = ResetOffsets.Request(UUID.randomUUID().toString, List("processor"))
        run(ResetOffsetsActions(app, request, "operator-pod", "cloudflow", Event.toObjectReference(app)))

        committed("shop.processor.in").values.sum mustBe Records
        events(ResetOffsetsActions.ResetFailedReason).exists(_.contains("scale its streamlet to 0")) mustBe true
        ResetOffsets.done(storedApp()) mustBe Some(request.id)
      } finally consumer.close()
    }
  }

  /** The application in the API server, and the processor's secret carrying its Kafka connection, as the CLI writes it.
    */
  private def deploy(topic: String): App.Cr = {
    val processor = randomStreamlet().asProcessor[Foo, Bar]
    val egress = randomStreamlet().asEgress[Bar]
    val processorRef = processor.ref("processor")
    val egressRef = egress.ref("egress")
    val spec = CloudflowApplicationSpecBuilder.create(
      "shop",
      "1",
      "image",
      Blueprint()
        .define(Vector(processor, egress))
        .use(processorRef)
        .use(egressRef)
        .connect(BTopic("foos", kafkaConfig = ConfigFactory.parseString(s"""topic.name = "$topic"""")), processorRef.in)
        .connect(BTopic("bars"), processorRef.out, egressRef.in)
        .verified
        .value,
      Map.empty)
    val app =
      App.Cr(_spec = spec, _metadata = new ObjectMetaBuilder().withName("shop").withNamespace(Namespace).build())
    client.resources(classOf[App.Cr]).inNamespace(Namespace).resource(app).createOrReplace()

    val deployment = spec.deployments.find(_.streamletName == "processor").value
    val secretConf =
      s"""cloudflow.runner.streamlet.context.port_mappings.in {
         |  id = "foos"
         |  config {
         |    topic.name = "$topic"
         |    bootstrap.servers = "${kafka.getBootstrapServers}"
         |  }
         |}""".stripMargin
    client
      .secrets()
      .inNamespace(Namespace)
      .resource(
        new SecretBuilder()
          .withMetadata(new ObjectMetaBuilder().withName(deployment.secretName).withNamespace(Namespace).build())
          .withData(Map("secret.conf" -> Base64Helper.encode(secretConf)).asJava)
          .build())
      .createOrReplace()
    storedApp()
  }

  private def storedApp(): App.Cr =
    client.resources(classOf[App.Cr]).inNamespace(Namespace).withName("shop").get()

  private def run(actions: Seq[Action]): Unit =
    Await.result(
      actions.foldLeft(Future.unit)((previous, action) => previous.flatMap(_ => executor.execute(action).map(_ => ()))),
      60.seconds)

  private def events(reason: String): List[String] =
    client
      .v1()
      .events()
      .inNamespace(Namespace)
      .list()
      .getItems
      .asScala
      .toList
      .filter((e: K8sEvent) => e.getReason == reason)
      .map(_.getMessage)

  private def newTopicWithRecords(): String = {
    val topic = s"foos-${UUID.randomUUID()}"
    admin.createTopics(List(new NewTopic(topic, Partitions, 1.toShort)).asJava).all().get()
    val producer = new KafkaProducer[String, String](
      props(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafka.getBootstrapServers),
      new StringSerializer,
      new StringSerializer)
    try (0 until Records).foreach(i => producer.send(new ProducerRecord(topic, s"key-$i", s"value-$i")).get())
    finally producer.close()
    topic
  }

  private def subscribedConsumer(topic: String, group: String): KafkaConsumer[String, String] = {
    // A group that has read before must start clean: every case uses the same group id on its own topic.
    admin
      .deleteConsumerGroups(List(group).asJava)
      .all()
      .toCompletionStage
      .handle((_, _) => ())
      .toCompletableFuture
      .get()
    val consumer = new KafkaConsumer[String, String](
      props(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafka.getBootstrapServers,
        ConsumerConfig.GROUP_ID_CONFIG -> group,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest",
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false"),
      new StringDeserializer,
      new StringDeserializer)
    consumer.subscribe(List(topic).asJava)
    consumer
  }

  private def readAll(consumer: KafkaConsumer[String, String]): Unit = {
    var read = 0
    val deadline = 30.seconds.fromNow
    while (read < Records && deadline.hasTimeLeft()) read += consumer.poll(Duration.ofMillis(200)).count()
    read mustBe Records
  }

  private def consumeEverythingAndCommit(topic: String, group: String): Unit = {
    val consumer = subscribedConsumer(topic, group)
    try {
      readAll(consumer)
      consumer.commitSync()
    } finally consumer.close()
  }

  private def committed(group: String): Map[Int, Long] =
    admin
      .listConsumerGroupOffsets(group)
      .partitionsToOffsetAndMetadata()
      .get()
      .asScala
      .map { case (partition, offset) => partition.partition -> offset.offset }
      .toMap

  private def props(entries: (String, String)*): Properties = {
    val p = new Properties()
    entries.foreach { case (k, v) => p.put(k, v) }
    p
  }
}
