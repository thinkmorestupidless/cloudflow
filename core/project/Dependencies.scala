import sbt.Keys._
import sbt._

object Dependencies {

  val Scala212 =
    "2.12.20" // retained for cloudflow-sbt-plugin (SBT 1.x plugin constraint); 2.12.20 has JDK 21 classfile-parser support
  val Scala213 =
    "2.13.16" // 2.13.14+ has JDK 21 support; bumped from 2.13.8 alongside 2.12 to keep cross-build consistent
  val Scala3 = "3.3.5"

  object Versions {
    // The same Pekko line as nakka, which these pipelines run beside.
    val pekko = "1.7.0"
    val pekkoHttp = "1.4.0"
    val pekkoGrpc = "1.2.0"
    val pekkoConnectorsKafka = "1.2.0"
    val pekkoMgmt = "1.2.1"
    val spark = "2.4.5"
    val fabric8 = "6.13.4"
    val jackson = "2.17.2"
    val jacksonDatabind = "2.17.2"
    val scalaTest = "3.2.18"
    val maven = "3.9.8"
  }

  object Compile {
    val fabric8KubernetesClient = "io.fabric8" % "kubernetes-client" % Versions.fabric8

    val typesafeConfig = "com.typesafe" % "config" % "1.4.3"
    val pureConfigCore = "com.github.pureconfig" %% "pureconfig-core" % "0.17.7"
    val pureConfigGenericScala3 = "com.github.pureconfig" %% "pureconfig-generic-scala3" % "0.17.7"
    val scopt =
      "com.github.scopt" %% "scopt" % "4.1.0" // FIXME generating docs for CLI fails with concurrent modification with 2.13 and this version.
    val airframeLog = "org.wvlet.airframe" %% "airframe-log" % "24.9.0"
    val asciiTable = "de.vandermeer" % "asciitable" % "0.3.2"

    val logback = "ch.qos.logback" % "logback-classic" % "1.5.6"

    val scalatest = "org.scalatest" %% "scalatest" % Versions.scalaTest
    val scalatestMustMatchers = "org.scalatest" %% "scalatest-mustmatchers" % Versions.scalaTest
    // These two dependencies are required to be present at runtime by fabric8, specifically its pod file read methods.
    // Reference:
    // https://github.com/fabric8io/kubernetes-client/blob/0c4513ff30ac9229426f1481a46fde2eb54933d9/kubernetes-client/src/main/java/io/fabric8/kubernetes/client/dsl/internal/core/v1/PodOperationsImpl.java#L451
    val commonsCodec = "commons-codec" % "commons-codec" % "1.17.1"
    val commonsCompress = "org.apache.commons" % "commons-compress" % "1.27.1"

    // Artifact IDs changed from jdk15on to jdk18on in BouncyCastle 1.72+
    val bouncyCastleCore = "org.bouncycastle" % "bcpkix-jdk18on" % "1.78.1"
    val bouncyCastleExt = "org.bouncycastle" % "bcprov-ext-jdk18on" % "1.78.1"

    val pekkoActor = "org.apache.pekko" %% "pekko-actor" % Versions.pekko
    val pekkoTestkit = "org.apache.pekko" %% "pekko-testkit" % Versions.pekko
    val pekkoStream = "org.apache.pekko" %% "pekko-stream" % Versions.pekko
    val pekkoStreamTestkit = "org.apache.pekko" %% "pekko-stream-testkit" % Versions.pekko
    val pekkoSlf4j = "org.apache.pekko" %% "pekko-slf4j" % Versions.pekko
    val pekkoDiscovery = "org.apache.pekko" %% "pekko-discovery" % Versions.pekko
    val pekkoShardingTyped = "org.apache.pekko" %% "pekko-cluster-sharding-typed" % Versions.pekko
    val pekkoCluster = "org.apache.pekko" %% "pekko-cluster" % Versions.pekko

    val pekkoHttp = "org.apache.pekko" %% "pekko-http" % Versions.pekkoHttp
    val pekkoHttpSprayJson = "org.apache.pekko" %% "pekko-http-spray-json" % Versions.pekkoHttp

    val pekkoConnectorsKafka = ("org.apache.pekko" %% "pekko-connectors-kafka" % Versions.pekkoConnectorsKafka)
      .exclude("com.fasterxml.jackson.core", "jackson-databind")
      .exclude("com.fasterxml.jackson.module", "jackson-module-scala")
    val pekkoConnectorsKafkaSharding =
      "org.apache.pekko" %% "pekko-connectors-kafka-cluster-sharding" % Versions.pekkoConnectorsKafka
    val pekkoConnectorsKafkaTestkit =
      ("org.apache.pekko" %% "pekko-connectors-kafka-testkit" % Versions.pekkoConnectorsKafka)
        .exclude("org.apache.pekko", "pekko-stream-testkit")

    val pekkoManagement = "org.apache.pekko" %% "pekko-management" % Versions.pekkoMgmt
    val pekkoClusterBootstrap = "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % Versions.pekkoMgmt
    val pekkoDiscoveryK8 = "org.apache.pekko" %% "pekko-discovery-kubernetes-api" % Versions.pekkoMgmt

    val pekkoGrpcRuntime = "org.apache.pekko" %% "pekko-grpc-runtime" % Versions.pekkoGrpc

    // akka-stream-contrib has no Scala 3 artifact; PartitionWith is inlined in SplitterLogic.scala.
    val avro = ("org.apache.avro" % "avro" % "1.12.0")
      .exclude("com.fasterxml.jackson.core", "jackson-databind")

    val jacksonCore = "com.fasterxml.jackson.core" % "jackson-core" % Versions.jackson
    val jacksonDatabind = "com.fasterxml.jackson.core" % "jackson-databind" % Versions.jacksonDatabind
    val jacksonScala = ("com.fasterxml.jackson.module" %% "jackson-module-scala" % Versions.jackson)
      .exclude("com.fasterxml.jackson.core", "jackson-databind")

    val slf4jApi = "org.slf4j" % "slf4j-api" % "2.0.3"
    val sprayJson = "io.spray" %% "spray-json" % "1.3.6"
    val scalaPbRuntime = "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion

    // bijection-avro has no Scala 3 artifact; Avro binary codec is inlined in AvroCodec.scala.

    val ficus = "com.iheart" %% "ficus" % "1.5.2"

    // kube-actions was vendored into cloudflow-operator for fabric8 6.x compatibility.
    val sourcecode = "com.lihaoyi" %% "sourcecode" % "0.3.1"

    // What pekko-connectors-kafka 1.2.0 is built and tested against; Kafka 4 brokers accept 3.9 clients.
    val kafkaClient = "org.apache.kafka" % "kafka-clients" % "3.9.2"
    // kafka-clients 4.x class files can't be parsed by the Scala 2.12 compiler; use 3.x for 2.12-only modules.
    val kafkaClient212 = "org.apache.kafka" % "kafka-clients" % "3.8.0"

    val classgraph = "io.github.classgraph" % "classgraph" % "4.8.176"

    val scalaPbCompilerPlugin = "com.thesamet.scalapb" %% "compilerplugin" % scalapb.compiler.Version.scalapbVersion
    val testcontainersKafka =
      "org.testcontainers" % "kafka" % "1.21.4" // 1.20.x hardcodes Docker API v1.41 in daemon-probe; Docker Engine 29 (min 1.44) rejects with 400. 1.21+ negotiates correctly.
    val asciigraphs = "com.github.mutcianm" %% "ascii-graphs" % "0.0.6"

    val mavenPluginApi = "org.apache.maven" % "maven-plugin-api" % Versions.maven
    val mavenCore = "org.apache.maven" % "maven-core" % Versions.maven
    val mavenEmbedder = "org.apache.maven" % "maven-embedder" % Versions.maven
    val mavenPluginAnnotations = "org.apache.maven.plugin-tools" % "maven-plugin-annotations" % "3.9.0"
    val mavenProject = "org.apache.maven" % "maven-project" % "2.2.1"
    val mojoExecutor = "org.twdata.maven" % "mojo-executor" % "2.4.0"
    val junit = "junit" % "junit" % "4.13.2"
  }

  /** Pekko checks at startup that every module of one family is on the same version, and eviction lifts only the
    * modules a build names directly: pekko-connectors-kafka 1.2.0 brings pekko-stream 1.1.5, and pekko-management
    * brings an older pekko-http. Pin both families whole, wherever they appear.
    */
  val pekkoFamilyOverrides: Seq[ModuleID] =
    Seq(
      "pekko-actor",
      "pekko-actor-typed",
      "pekko-stream",
      "pekko-stream-typed",
      "pekko-slf4j",
      "pekko-protobuf-v3",
      "pekko-discovery",
      "pekko-cluster",
      "pekko-cluster-typed",
      "pekko-cluster-tools",
      "pekko-cluster-sharding",
      "pekko-cluster-sharding-typed",
      "pekko-coordination",
      "pekko-distributed-data",
      "pekko-remote",
      "pekko-pki",
      "pekko-serialization-jackson",
      "pekko-testkit",
      "pekko-stream-testkit").map(m => "org.apache.pekko" %% m % Versions.pekko) ++
      Seq(
        "pekko-http",
        "pekko-http-core",
        "pekko-parsing",
        "pekko-http-spray-json",
        "pekko-http-jackson",
        "pekko-http-testkit")
        .map(m => "org.apache.pekko" %% m % Versions.pekkoHttp)

  object TestDeps {

    val fabric8KubernetesServerMock = "io.fabric8" % "kubernetes-server-mock" % Versions.fabric8 % Test

    val pekkoHttpJackson = "org.apache.pekko" %% "pekko-http-jackson" % Versions.pekkoHttp % Test

    val pekkoHttpTestkit = "org.apache.pekko" %% "pekko-http-testkit" % Versions.pekkoHttp % Test

    val avro4s = "com.sksamuel.avro4s" %% "avro4s-core" % "5.0.15" % Test
    val avro4s212 = "com.sksamuel.avro4s" %% "avro4s-core" % "4.1.0" % Test

    val scalatestJunit = "org.scalatestplus" %% "junit-4-13" % s"${Versions.scalaTest}.0" % Test

    val jodaTime = "joda-time" % "joda-time" % "2.10.6"
  }

  val cloudflowAvro =
    libraryDependencies ++= Seq(Compile.avro)

  val cloudflowConfig =
    libraryDependencies ++= Seq(
      Compile.fabric8KubernetesClient,
      Compile.jacksonScala,
      Compile.jacksonDatabind,
      Compile.typesafeConfig,
      Compile.pureConfigCore,
      Compile.pureConfigGenericScala3,
      Compile.scalatest % Test)

  val cloudflowCli =
    libraryDependencies ++= Seq(
      Compile.logback,
      Compile.scopt,
      Compile.airframeLog,
      Compile.asciiTable,
      Compile.bouncyCastleCore,
      Compile.bouncyCastleExt,
      TestDeps.fabric8KubernetesServerMock,
      Compile.scalatest % Test)

  val cloudflowCrd =
    libraryDependencies ++= Seq(
      Compile.fabric8KubernetesClient,
      Compile.jacksonScala,
      Compile.jacksonDatabind,
      Compile.scalatest % Test)

  val cloudflowIt =
    libraryDependencies ++= Seq(Compile.commonsCodec % Test, Compile.commonsCompress % Test, Compile.scalatest % Test)

  val cloudflowNewItLibrary =
    libraryDependencies ++= Seq(Compile.commonsCodec, Compile.commonsCompress, Compile.scalatest)

  val cloudflowBlueprint =
    libraryDependencies ++= Seq(
      Compile.typesafeConfig,
      Compile.sprayJson,
      // TODO: check if Avro and ScalaPB can stay in a separate module
      Compile.avro,
      Compile.jacksonDatabind,
      Compile.scalaPbRuntime,
      Compile.logback % Test,
      Compile.scalatest % Test)
  // avro4s and kafka test deps are added conditionally per Scala version in build.sbt
  // (avro4s 5.0.15 has no 2.12 artifact; kafka-clients 4.x class files can't be parsed by Scala 2.12)

  val cloudflowOperator =
    libraryDependencies ++= Seq(
      Compile.pekkoActor,
      Compile.pekkoStream,
      Compile.pekkoHttp,
      Compile.pekkoSlf4j,
      Compile.logback,
      Compile.jacksonScala,
      Compile.jacksonDatabind,
      Compile.sourcecode,
      Compile.kafkaClient,
      Compile.scalatest % Test,
      TestDeps.avro4s)

  val cloudflowExtractor =
    libraryDependencies ++= Seq(Compile.typesafeConfig, Compile.classgraph, Compile.scalatest % Test)

  val cloudflowProto =
    libraryDependencies ++= Seq(Compile.scalaPbRuntime)

  val cloudflowSbtPlugin =
    libraryDependencies ++= Seq(
      Compile.scalaPbCompilerPlugin,
      Compile.asciigraphs,
      Compile.testcontainersKafka,
      Compile.kafkaClient212, // Scala 2.12 SBT plugin: kafka 3.x class files required
      Compile.scalatest % Test)

  val cloudflowRunnerConfig =
    libraryDependencies ++= Seq(
      Compile.jacksonScala,
      Compile.jacksonDatabind,
      Compile.typesafeConfig % Test,
      Compile.scalatest % Test)

  val cloudflowStreamlet =
    libraryDependencies ++= Seq(
      Compile.sprayJson,
      Compile.typesafeConfig,
      Compile.slf4jApi,
      Compile.ficus,
      Compile.scalatest % Test)

  val cloudflowAkka =
    libraryDependencies ++= Seq(
      Compile.pekkoActor,
      Compile.pekkoStream,
      Compile.pekkoSlf4j,
      Compile.pekkoDiscovery,
      Compile.pekkoHttp,
      Compile.pekkoHttpSprayJson,
      Compile.pekkoConnectorsKafka,
      Compile.pekkoConnectorsKafkaSharding,
      Compile.pekkoShardingTyped,
      Compile.pekkoCluster,
      Compile.pekkoManagement,
      Compile.pekkoClusterBootstrap,
      Compile.pekkoDiscoveryK8,
      Compile.logback,
      Compile.jacksonScala,
      Compile.jacksonDatabind,
      Compile.sprayJson,
      Compile.ficus)

  val cloudflowAkkaTestkit =
    libraryDependencies ++= Seq(
      Compile.pekkoSlf4j,
      Compile.pekkoStream,
      Compile.ficus,
      Compile.pekkoConnectorsKafkaTestkit,
      Compile.pekkoStreamTestkit,
      Compile.pekkoTestkit,
      Compile.scalatest,
      Compile.scalatestMustMatchers % "test",
      Compile.scalatest % Test,
      TestDeps.scalatestJunit)

  val cloudflowAkkaUtil =
    libraryDependencies ++= Vector(
      Compile.pekkoHttp,
      Compile.pekkoGrpcRuntime,
      Compile.pekkoStreamTestkit % Test,
      Compile.scalatest % Test,
      TestDeps.pekkoHttpTestkit,
      TestDeps.pekkoHttpJackson,
      TestDeps.scalatestJunit)

  val cloudflowAkkaTests =
    libraryDependencies ++= Vector(
      TestDeps.pekkoHttpTestkit,
      Compile.pekkoHttpSprayJson % Test,
      Compile.testcontainersKafka % Test,
      Compile.testcontainersKafka % Test,
      Compile.scalatest % Test,
      TestDeps.scalatestJunit)

  val cloudflowCrGenerator =
    libraryDependencies += Compile.scopt

  val cloudflowMavenPlugin =
    libraryDependencies ++= Seq(
      Compile.junit,
      Compile.mavenCore,
      Compile.mavenEmbedder,
      Compile.mavenProject,
      Compile.mavenPluginApi,
      Compile.mojoExecutor,
      Compile.mavenPluginAnnotations)

  val cloudflowBuildSupport =
    libraryDependencies ++= Seq(
      Compile.typesafeConfig,
      Compile.asciigraphs,
      Compile.testcontainersKafka,
      Compile.kafkaClient212
    ) // Scala 2.12 SBT plugin support: kafka 3.x class files required
}
