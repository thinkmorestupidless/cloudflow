/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package cloudflow.cli

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.nowarn
import scala.util.{ Success, Try }
import cloudflow.crd.{ App, ResetOffsets }
import cloudflow.cli.kubeclient.KubeClient
import cloudflow.cli.models.{ ApplicationStatus, ContainersReady, PodStatus, StreamletStatus }
import buildinfo.BuildInfo
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest._
import matchers.should._

class CliWorkflowSpec extends AnyFlatSpec with Matchers with TryValues {

  val crFile = new File("./cloudflow-cli/src/test/resources/swiss-knife.json")
  val crFileWithKafkaCluster = new File("./cloudflow-cli/src/test/resources/swiss-knife-with-kafka-cluster.json")
  val testingCRSummary = models.CRSummary("foo", "bar", "0.0.1", "now")

  val listResult = List(testingCRSummary)
  val statusResult = ApplicationStatus(testingCRSummary, "MOCK", List(), List())

  val defaultPvcMounts = List("cloudflow-spark", "cloudflow-flink")

  val defaultProvidedKafkaClusters = Map("default" -> """bootstrap.servers = "localhost:9092"""")

  @nowarn def testingKubeClientFactory(
      protocolVersion: String = App.ProtocolVersion,
      providedPvcs: List[String] = defaultPvcMounts,
      providedKafkaClusters: Map[String, String] = defaultProvidedKafkaClusters,
      providedApplication: Option[App.Cr] = None,
      providedInputSecret: String = "",
      providedStatus: ApplicationStatus = statusResult,
      updatedApplication: AtomicReference[Option[App.Cr]] = new AtomicReference(None))(
      config: Option[File],
      logger: CliLogger) = {
    new KubeClient {
      def listCloudflowApps(namespace: Option[String]): Try[List[models.CRSummary]] =
        Success(listResult)
      def getCloudflowAppStatus(app: String, namespace: String) =
        Success(providedStatus)
      def createImagePullSecret(
          namespace: String,
          dockerRegistryURL: String,
          dockerUsername: String,
          dockerPassword: String): Try[Unit] = Success(())
      def createNamespace(name: String): Try[Unit] = Success(())
      def getOperatorProtocolVersion(namespace: Option[String]): Try[String] = Success(protocolVersion)
      def createCloudflowApp(spec: App.Spec, namespace: String) = Success("1")
      def uidCloudflowApp(name: String, namespace: String) = Success("1")
      def createMicroservicesApp(cfSpec: App.Spec, namespace: String): Try[String] =
        Success("1")
      def configureCloudflowApp(
          name: String,
          namespace: String,
          appUid: String,
          appConfig: String,
          loggingContent: Option[String],
          configs: Map[App.Deployment, Map[String, String]]): Try[Unit] = Success(())
      def deleteCloudflowApp(app: String, namespace: String) = Success(())
      def getPvcs(namespace: String) = Success(providedPvcs)
      def getKafkaClusters(namespace: Option[String]) = Success(providedKafkaClusters)
      def readCloudflowApp(name: String, namespace: String): Try[Option[App.Cr]] = Success(providedApplication)
      def updateCloudflowApp(app: App.Cr, namespace: String): Try[App.Cr] = {
        updatedApplication.set(Some(app))
        Success(app)
      }
      def getAppInputSecret(name: String, namespace: String): Try[String] = Success(providedInputSecret)
    }
  }

  "The Cli" should "return the current version" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands.Version())
    // Assert
    res.isSuccess shouldBe true
    res.success.value.version shouldBe BuildInfo.version
  }

  it should "list mocked CRs" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands.List())

    // Assert
    res.isSuccess shouldBe true
    res.success.value.summaries shouldBe listResult
  }

  it should "get a mocked status" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands.Status(cloudflowApp = "test"))

    // Assert
    res.isSuccess shouldBe true
    res.success.value.status shouldBe statusResult
  }

  it should "run a mocked deploy" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands.Deploy(crFile = crFile))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked deploy if protocol version is incompatible" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory(protocolVersion = "-1"))

    // Act
    val res =
      cli.run(commands.Deploy(crFile = crFile))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("version of kubectl cloudflow is not compatible") shouldBe true
  }

  it should "succeed a mocked deploy" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands.Deploy(crFile = crFile))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "succeed a mocked deploy if provided configuration contains existent streamlets" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(
        commands
          .Deploy(
            crFile = crFile,
            configKeys = Map(
              "cloudflow.streamlets.pekko-process.config-parameters" -> "{ configurable-message = value1 }",
              "cloudflow.streamlets.flink-process.config-parameters" -> "{ configurable-message = value2 }")))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked deploy if provided configuration contains inexistent streamlets" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(
        commands
          .Deploy(
            crFile = crFile,
            configKeys = Map("cloudflow.streamlets.my-streamlet.config-parameters" -> "{ key1 = value1 }")))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("my-streamlet") shouldBe true
  }

  it should "fail a mocked deploy if it mention an unexistent pvc" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(
        commands
          .Deploy(
            crFile = crFile,
            configKeys = Map(
              "cloudflow.streamlets.pekko-process.kubernetes.pods.pod.volumes.foo" -> "{ pvc { name = non-existent-mnt } }")))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("non-existent-mnt") shouldBe true
  }

  it should "succeed a mocked deploy if it mention an existent pvc" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory(providedPvcs = defaultPvcMounts :+ "existent-mnt"))

    // Act
    val res =
      cli.run(commands
        .Deploy(
          crFile = crFile,
          configKeys = Map(
            "cloudflow.streamlets.pekko-process.kubernetes.pods.pod.volumes.foo" -> "{ pvc { name = existent-mnt } }")))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked deploy if it mention an unexistent topic" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(
        commands
          .Deploy(crFile = crFile, configKeys = Map("cloudflow.topics.not-existent-topic" -> "{ }")))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("not-existent-topic") shouldBe true
  }

  it should "succeed a mocked deploy if it mention a existent topic" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(
        commands
          .Deploy(crFile = crFile, configKeys = Map("cloudflow.topics.pekko-pipe" -> "{ }")))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked deploy if the cr contains a non existent kafka cluster" in {
    // Arrange
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(
        commands
          .Deploy(crFile = crFileWithKafkaCluster))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("example-kafka-cluster") shouldBe true
  }

  it should "succeed a mocked deploy if the cr contains an existent kafka cluster" in {
    // Arrange
    val cli = new TestingCli(
      testingKubeClientFactory(providedKafkaClusters = defaultProvidedKafkaClusters ++
        Map("example-kafka-cluster" -> """bootstrap.servers = "localhost:9092"""")))

    // Act
    val res =
      cli.run(
        commands
          .Deploy(crFile = crFileWithKafkaCluster))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked scale if it contains non-existent streamlet" in {
    // Arrange
    val appCr = Json.mapper.readValue(crFile, classOf[App.Cr])
    val cli = new TestingCli(testingKubeClientFactory(providedApplication = Some(appCr)))

    // Act
    val res =
      cli.run(commands.Scale("skiss-knife", scales = Map("non-existent" -> 5)))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("non-existent") shouldBe true
  }

  it should "succeed a mocked scale if it contains an existent streamlet" in {
    // Arrange
    val appCr = Json.mapper.readValue(crFile, classOf[App.Cr])
    val cli = new TestingCli(testingKubeClientFactory(providedApplication = Some(appCr)))

    // Act
    val res =
      cli.run(commands.Scale("skiss-knife", scales = Map("pekko-process" -> 5)))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked configuration if it contains non-existent volume" in {
    // Arrange
    val appCr = Json.mapper.readValue(crFile, classOf[App.Cr])
    val configKey =
      "cloudflow.streamlets.pekko-process.kubernetes.pods.pod.volumes.default.pvc.name" -> "non-existent-pvc"
    val cli = new TestingCli(testingKubeClientFactory(providedApplication = Some(appCr)))

    // Act
    val res =
      cli.run(commands.Configure(cloudflowApp = "skiss-knife", configKeys = Map(configKey)))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("non-existent-pvc") shouldBe true
  }

  it should "succeed a mocked configuration if it contains an existent volume" in {
    // Arrange
    val appCr = Json.mapper.readValue(crFile, classOf[App.Cr])
    val configKey =
      "cloudflow.streamlets.pekko-process.kubernetes.pods.pod.volumes.default.pvc.name" -> "existent-pvc"
    val cli =
      new TestingCli(
        testingKubeClientFactory(providedPvcs = defaultPvcMounts :+ "existent-pvc", providedApplication = Some(appCr)))

    // Act
    val res =
      cli.run(commands.Configure(cloudflowApp = "skiss-knife", configKeys = Map(configKey)))

    // Assert
    res.isSuccess shouldBe true
  }

  it should "fail a mocked deploy if the version can not be parsed correctly" in {
    // Arrange
    val invalidCrFile = new File("./cloudflow-cli/src/test/resources/invalid-cr1.json")
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands.Deploy(crFile = invalidCrFile))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains("spec.version is invalid") shouldBe true
  }

  it should "fail a mocked deploy if the library version contains spaces" in {
    // Arrange
    val invalidCrFile = new File("./cloudflow-cli/src/test/resources/invalid-cr2.json")
    val cli = new TestingCli(testingKubeClientFactory())

    // Act
    val res =
      cli.run(commands.Deploy(crFile = invalidCrFile))

    // Assert
    res.isFailure shouldBe true
    res.failure.exception.getMessage.contains(" spec.library_version is missing, empty or invalid") shouldBe true
  }

  private def swissKnife(scaledToZero: Set[String]): App.Cr = {
    val cr = Json.mapper.readValue(crFile, classOf[App.Cr])
    cr.setSpec(cr.getSpec.copy(deployments = cr.getSpec.deployments.map { d =>
      if (scaledToZero.contains(d.streamletName)) d.copy(replicas = Some(0)) else d
    }))
    cr
  }

  private val pekkoReaders =
    Set("flink-egress", "spark-egress", "pekko-process", "raw-egress", "pekko-config-output", "pekko-egress")

  it should "request an offset reset for stopped streamlets, recording it on the application" in {
    val updated = new AtomicReference[Option[App.Cr]](None)
    val cli = new TestingCli(
      testingKubeClientFactory(
        providedApplication = Some(swissKnife(Set("pekko-process"))),
        updatedApplication = updated))

    val res = cli.run(commands.ResetOffsets("swiss-knife", streamlets = List("pekko-process")))

    res.isSuccess shouldBe true
    res.success.value.streamlets shouldBe List("pekko-process")
    val request = ResetOffsets.request(updated.get.get).get
    request.id shouldBe res.success.value.requestId
    request.streamlets shouldBe List("pekko-process")
    ResetOffsets.pending(updated.get.get) shouldBe Some(request)
  }

  it should "request a reset of every streamlet that reads when none is named, once all of them are stopped" in {
    val updated = new AtomicReference[Option[App.Cr]](None)
    val cli = new TestingCli(
      testingKubeClientFactory(providedApplication = Some(swissKnife(pekkoReaders)), updatedApplication = updated))

    val res = cli.run(commands.ResetOffsets("swiss-knife"))

    res.isSuccess shouldBe true
    res.success.value.streamlets should contain theSameElementsAs pekkoReaders
    ResetOffsets.request(updated.get.get).get.streamlets shouldBe empty
  }

  it should "refuse to reset a streamlet that is not scaled to 0, saying how to stop it, and record nothing" in {
    val updated = new AtomicReference[Option[App.Cr]](None)
    val cli = new TestingCli(
      testingKubeClientFactory(providedApplication = Some(swissKnife(Set.empty)), updatedApplication = updated))

    val res = cli.run(commands.ResetOffsets("swiss-knife", streamlets = List("pekko-process")))

    res.isFailure shouldBe true
    res.failure.exception.getMessage should include("[pekko-process] is not scaled to 0")
    res.failure.exception.getMessage should include("kubectl cloudflow scale swiss-knife pekko-process=0")
    updated.get shouldBe None
  }

  it should "refuse to reset a streamlet whose pods have not gone yet" in {
    val status = statusResult.copy(streamletsStatuses = List(
      StreamletStatus("pekko-process", List(PodStatus("pekko-process-0", ContainersReady(0, 1), "Terminating", 0)))))
    val cli = new TestingCli(
      testingKubeClientFactory(providedApplication = Some(swissKnife(Set("pekko-process"))), providedStatus = status))

    val res = cli.run(commands.ResetOffsets("swiss-knife", streamlets = List("pekko-process")))

    res.isFailure shouldBe true
    res.failure.exception.getMessage should include("[pekko-process] still has 1 pod(s)")
  }

  it should "refuse streamlets with no consumer groups to reset, naming each" in {
    val cli = new TestingCli(testingKubeClientFactory(providedApplication = Some(swissKnife(Set("ingress")))))

    val res = cli.run(commands.ResetOffsets("swiss-knife", streamlets = List("nope", "spark-process", "ingress")))

    res.isFailure shouldBe true
    val message = res.failure.exception.getMessage
    message should include("no streamlet [nope]")
    message should include("streamlet [spark-process] runs on the spark runtime")
    message should include("streamlet [ingress] runs on the spark runtime")
  }
}
