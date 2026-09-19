Global / cancelable := true

// The version comes from git alone, via sbt-dynver: exactly `0.2.0` on a commit tagged `v0.2.0`,
// `0.2.0-3-abc1234` three commits later, with `-dirty...` for uncommitted changes. The separator is
// `-` rather than dynver's default `+`, which a Docker image tag cannot contain.
ThisBuild / dynverSeparator := "-"

ThisBuild / dependencyOverrides ++= Dependencies.pekkoFamilyOverrides

ThisBuild / credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  "x-access-token",
  sys.env.getOrElse("GITHUB_TOKEN", ""))

lazy val tooling =
  Project(id = "tooling", base = file("tooling"))
    .dependsOn(cloudflowCli)
    .settings(scalaVersion := Dependencies.Scala3)

lazy val cloudflowCrd =
  Project(id = "cloudflow-crd", base = file("cloudflow-crd"))
    .settings(Dependencies.cloudflowCrd)
    .settings(name := "cloudflow-crd", scalaVersion := Dependencies.Scala3, Defaults.itSettings)

lazy val cloudflowConfig =
  Project(id = "cloudflow-config", base = file("cloudflow-config"))
    .settings(Dependencies.cloudflowConfig)
    .settings(name := "cloudflow-config", scalaVersion := Dependencies.Scala3)
    .dependsOn(cloudflowCrd)

val getMuslBundle = taskKey[Unit]("Fetch Musl bundle")
val winPackageBin = taskKey[Unit]("PackageBin Graal on Windows")

lazy val cloudflowCli =
  Project(id = "cloudflow-cli", base = file("cloudflow-cli"))
    .settings(Dependencies.cloudflowCli)
    .settings(name := "kubectl-cloudflow")
    .settings(
      scalaVersion := Dependencies.Scala3,
      Compile / mainClass := Some("cloudflow.cli.Main"),
      Compile / discoveredMainClasses := Seq(),
      run / fork := true,
      getMuslBundle := {
        if (!((ThisProject / baseDirectory).value / "src" / "graal" / "bundle").exists && graalVMNativeImageGraalVersion.value.isDefined) {
          TarDownloader.downloadAndExtract(
            new URL("https://github.com/gradinac/musl-bundle-example/releases/download/v1.0/musl.tar.gz"),
            (ThisProject / baseDirectory).value / "src" / "graal")
        }
      },
      GraalVMNativeImage / packageBin := {
        if (graalVMNativeImageGraalVersion.value.isDefined) {
          (GraalVMNativeImage / packageBin).dependsOn(getMuslBundle).value
        } else {
          (GraalVMNativeImage / packageBin).value
        }
      },
      graalVMNativeImageOptions := Seq(
        "--verbose",
        "--no-server",
        "--enable-http",
        "--enable-https",
        "--enable-url-protocols=http,https,file,jar",
        "--enable-all-security-services",
        "-H:+JNI",
        "-H:IncludeResourceBundles=com.sun.org.apache.xerces.internal.impl.msg.XMLMessages",
        "-H:+ReportExceptionStackTraces",
        "--no-fallback",
        "--initialize-at-build-time",
        "--report-unsupported-elements-at-runtime",
        // TODO: possibly to be removed
        "--allow-incomplete-classpath",
        "--initialize-at-run-time" + Seq(
          "cloudflow.config.CloudflowConfig$",
          "cloudflow.config.UnsafeCloudflowConfigLoader$",
          "com.typesafe.config.impl.ConfigImpl",
          "com.typesafe.config.impl.ConfigImpl$EnvVariablesHolder",
          "com.typesafe.config.impl.ConfigImpl$SystemPropertiesHolder",
          "com.typesafe.config.impl.ConfigImpl$LoaderCacheHolder",
          "io.fabric8.kubernetes.client.internal.CertUtils$1").mkString("=", ",", "")),
      GraalVMNativeImage / winPackageBin := {
        val targetDirectory = target.value
        val binaryName = name.value
        val nativeImageCommand = graalVMNativeImageCommand.value
        val className = (Compile / mainClass).value.getOrElse(sys.error("Could not find a main class."))
        val classpathJars = scriptClasspathOrdering.value
        val extraOptions = graalVMNativeImageOptions.value
        val streams = Keys.streams.value
        val dockerCommand = DockerPlugin.autoImport.dockerExecCommand.value

        targetDirectory.mkdirs()
        val temp = IO.createTemporaryDirectory

        try {
          classpathJars.foreach { case (f, _) =>
            IO.copyFile(f, temp / f.getName)
          }

          val command = {
            val nativeImageArguments = {
              Seq(
                "--class-path",
                s""""${(temp / "*").getAbsolutePath}"""",
                s"-H:Name=$binaryName") ++ extraOptions ++ Seq(className)
            }
            Seq(nativeImageCommand) ++ nativeImageArguments
          }

          sys.process.Process(command, targetDirectory).! match {
            case 0 => targetDirectory / binaryName
            case x => sys.error(s"Failed to run $command, exit status: " + x)
          }
        } finally {
          temp.delete()
        }
      })
    .enablePlugins(BuildInfoPlugin, GraalVMNativeImagePlugin)
    .dependsOn(cloudflowConfig, cloudflowRunnerConfig)

lazy val cloudflowIt =
  Project(id = "cloudflow-it", base = file("cloudflow-it"))
    .configs(IntegrationTest.extend(Test))
    .settings(Defaults.itSettings, Dependencies.cloudflowIt)
    .settings(
      scalaVersion := Dependencies.Scala3,
      inConfig(IntegrationTest)(org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings),
      IntegrationTest / fork := true)
    .dependsOn(cloudflowCli)

lazy val cloudflowNewItLibrary =
  Project(id = "cloudflow-new-it-library", base = file("cloudflow-new-it-library"))
    .settings(Dependencies.cloudflowNewItLibrary)
    .settings(scalaVersion := Dependencies.Scala3)
    .dependsOn(cloudflowCli)

lazy val cloudflowNewIt =
  Project(id = "cloudflow-new-it", base = file("cloudflow-new-it"))
    .settings(
      // ScriptedPlugin runs inside the SBT process (Scala 2.12); must stay at 2.12.
      scalaVersion := Dependencies.Scala212,
      scriptedLaunchOpts := {
        scriptedLaunchOpts.value ++
        Seq(
          "-Xmx1024M",
          "-Dscripted=true",
          "-Dcloudflow.version=" + sys.env.get("CLOUDFLOW_VERSION").getOrElse("not-defined-cloudflow-version"),
          "-Dlibrary.version=" + version.value)
      },
      scriptedBufferLog := false,
      scriptedDependencies := {
        // This cleanup the directories for local development
        import scala.sys.process._
        val ignoredFiles = "git status --ignored --porcelain".!!
        if (!ignoredFiles.isEmpty) {
          IO.delete(
            ignoredFiles
              .split("\n")
              .filter(_.startsWith("!! cloudflow-new-it/src/sbt-test"))
              .map { f => file(f.replaceFirst("!! ", "")) })
        }

        val _1 = (ThisProject / scriptedDependencies).value
        val _2 = (cloudflowCrd / publishLocal).value
        val _3 = (cloudflowConfig / publishLocal).value
        val _4 = (cloudflowCli / publishLocal).value
        val _5 = (cloudflowNewItLibrary / publishLocal).value
      },
      // the following settings are to run the tests in parallel
      // tuned to run against a real cluster (for now)
      scriptedBatchExecution := true,
      scriptedParallelInstances := 1)
    .enablePlugins(ScriptedPlugin)

// makePom fails, often with: java.lang.StringIndexOutOfBoundsException: String index out of range: 0
addCommandAlias(
  "winGraalBuild",
  s"""project cloudflow-cli; set makePom / publishArtifact := false; set graalVMNativeImageCommand := "${sys.env
      .get("JAVA_HOME")
      .getOrElse("")
      .replace("""\""", """\\\\""")}\\\\bin\\\\native-image.cmd"; graalvm-native-image:winPackageBin""")

addCommandAlias(
  "linuxStaticBuild",
  """project cloudflow-cli; set graalVMNativeImageGraalVersion := Some("20.1.0-java11"); set graalVMNativeImageOptions ++= Seq("--static", "-H:UseMuslC=/opt/graalvm/stage/resources/bundle/"); graalvm-native-image:packageBin""")

addCommandAlias(
  "regenerateGraalVMConfig",
  s""";project tooling ; set run / fork := true; set run / javaOptions += "-agentlib:native-image-agent=config-output-dir=${file(
      ".").getAbsolutePath}/cloudflow-cli/src/main/resources/META-INF/native-image"; runMain cli.CodepathCoverageMain""")

// cloudflowBlueprint cross-compiles for Scala 2.12 (consumed by cloudflow-sbt-plugin),
// Scala 2.13 (legacy cross-build retained), and Scala 3 (consumed by operator/localrunner).
// sbt-cross is retained solely for this project; all other modules are Scala 3 only.
lazy val cloudflowBlueprintCross = cloudflowBlueprint.cross
lazy val cloudflowBlueprint213 = cloudflowBlueprintCross(Dependencies.Scala213)
lazy val cloudflowBlueprint212 = cloudflowBlueprintCross(Dependencies.Scala212)
lazy val cloudflowBlueprint3 = cloudflowBlueprintCross(Dependencies.Scala3)

lazy val cloudflowAvro =
  Project(id = "cloudflow-avro", base = file("cloudflow-avro"))
    .dependsOn(cloudflowStreamlets)
    .enablePlugins(GenJavadocPlugin, ScalafmtPlugin)
    .settings(Dependencies.cloudflowAvro)
    .settings(scalaVersion := Dependencies.Scala3, scalafmtOnCompile := true)

lazy val cloudflowJson =
  Project(id = "cloudflow-json", base = file("cloudflow-json"))
    .dependsOn(cloudflowStreamlets)
    .enablePlugins(ScalafmtPlugin)
    .settings(Dependencies.cloudflowJson)
    .settings(scalaVersion := Dependencies.Scala3, scalafmtOnCompile := true)

lazy val cloudflowBlueprint =
  Project(id = "cloudflow-blueprint", base = file("cloudflow-blueprint"))
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin)
    .settings(Dependencies.cloudflowBlueprint)
    .settings(
      scalaVersion := Dependencies.Scala212,
      crossScalaVersions := Vector(Dependencies.Scala212, Dependencies.Scala213, Dependencies.Scala3),
      scalafmtOnCompile := true,
      buildInfoKeys := Seq[BuildInfoKey](name, version),
      buildInfoPackage := "cloudflow.blueprint",
      // avro4s 5.x is Scala 3 only; kafka-clients 4.x class files can't be parsed by Scala 2.12 compiler.
      libraryDependencies += (
        if (scalaVersion.value.startsWith("2."))
          Dependencies.TestDeps.avro4s212
        else
          Dependencies.TestDeps.avro4s
      ),
      libraryDependencies += (
        if (scalaVersion.value == Dependencies.Scala212)
          Dependencies.Compile.kafkaClient212 % Test
        else
          Dependencies.Compile.kafkaClient % Test
      ))

lazy val cloudflowOperator =
  Project(id = "cloudflow-operator", base = file("cloudflow-operator"))
    .enablePlugins(ScalafmtPlugin, BuildInfoPlugin, JavaServerAppPackaging, DockerPlugin, AshScriptPlugin)
    .dependsOn(cloudflowConfig, cloudflowBlueprint3)
    .settings(Dependencies.cloudflowOperator)
    .settings(
      scalaVersion := Dependencies.Scala3,
      scalafmtOnCompile := true,
      run / fork := true,
      Global / cancelable := true,
      buildInfoKeys := Seq[BuildInfoKey](
        name,
        version,
        scalaVersion,
        sbtVersion,
        BuildInfoKey.action("buildTime") {
          java.time.Instant.now().toString
        },
        BuildInfoKey.action("buildUser") {
          sys.props.getOrElse("user.name", "unknown")
        }),
      buildInfoPackage := "cloudflow.operator")
    .settings(
      Docker / packageName := "cloudflow-operator",
      dockerUpdateLatest := true,
      dockerUsername := sys.props.get("docker.username"),
      dockerRepository := sys.props.get("docker.registry"),
      dockerBaseImage := "eclipse-temurin:11-jre-focal")
    .settings(dependencyOverrides ++= Seq("org.yaml" % "snakeyaml" % "2.0"))
    .settings(
      Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
      // Suites here start their own Kafka container; one at a time, as in cloudflow-pekko-tests.
      Test / parallelExecution := false)

// cloudflow-extractor, cloudflow-build-support, cloudflow-cr-generator, and
// cloudflow-maven-plugin remain on Scala 2.12 because cloudflow-sbt-plugin (an SBT 1.x
// plugin, which must compile with Scala 2.12) depends on them.
lazy val cloudflowExtractor =
  Project(id = "cloudflow-extractor", base = file("cloudflow-extractor"))
    .enablePlugins(ScalafmtPlugin, BuildInfoPlugin)
    .settings(Dependencies.cloudflowExtractor)
    .settings(
      scalaVersion := Dependencies.Scala212,
      scalafmtOnCompile := true,
      run / fork := true,
      Global / cancelable := true)

lazy val cloudflowProto =
  Project(id = "cloudflow-proto", base = file("cloudflow-proto"))
    .dependsOn(cloudflowStreamlets)
    .enablePlugins(GenJavadocPlugin, ScalafmtPlugin)
    .settings(Dependencies.cloudflowProto)
    .settings(scalaVersion := Dependencies.Scala3, scalafmtOnCompile := true)

lazy val cloudflowSbtPlugin =
  Project(id = "cloudflow-sbt-plugin", base = file("cloudflow-sbt-plugin"))
    .settings(name := "sbt-cloudflow")
    .dependsOn(cloudflowBlueprint, cloudflowExtractor, cloudflowBuildSupport)
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin, SbtPlugin)
    .settings(Dependencies.cloudflowSbtPlugin)
    .settings(
      scalaVersion := Dependencies.Scala212,
      scalafmtOnCompile := true,
      sbtPlugin := true,
      crossSbtVersions := Vector("1.4.9"),
      buildInfoKeys := Seq[BuildInfoKey](version),
      buildInfoPackage := "cloudflow.sbt",
      addSbtPlugin("se.marcuslonnberg" % "sbt-docker" % "1.9.0"),
      addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.3.25"),
      addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.2.0"),
      scriptedLaunchOpts := {
        scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
      },
      scriptedBufferLog := false)

lazy val cloudflowRunnerConfig =
  Project(id = "cloudflow-runner-config", base = file("cloudflow-runner-config"))
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin)
    .settings(Dependencies.cloudflowRunnerConfig)
    .settings(scalaVersion := Dependencies.Scala3, scalafmtOnCompile := true)

lazy val cloudflowStreamlets =
  Project(id = "cloudflow-streamlets", base = file("cloudflow-streamlets"))
    .enablePlugins(GenJavadocPlugin, ScalafmtPlugin)
    .settings(Dependencies.cloudflowStreamlet)
    .settings(scalaVersion := Dependencies.Scala3, scalafmtOnCompile := true)

lazy val cloudflowPekko =
  Project(id = "cloudflow-pekko", base = file("cloudflow-pekko"))
    .enablePlugins(GenJavadocPlugin, JavaFormatterPlugin, ScalafmtPlugin)
    .dependsOn(cloudflowStreamlets)
    .settings(Dependencies.cloudflowPekko)
    .settings(scalaVersion := Dependencies.Scala3, javacOptions += "-Xlint:deprecation", scalafmtOnCompile := true)

lazy val cloudflowPekkoTestkit =
  Project(id = "cloudflow-pekko-testkit", base = file("cloudflow-pekko-testkit"))
    .enablePlugins(GenJavadocPlugin, JavaFormatterPlugin, ScalafmtPlugin)
    .dependsOn(cloudflowPekko, (cloudflowAvro % "test->test").classpathDependency)
    .settings(Dependencies.cloudflowPekkoTestkit)
    .settings(
      scalaVersion := Dependencies.Scala3,
      scalafmtOnCompile := true,
      javacOptions ++= Seq("-Xlint:deprecation", "-Xlint:unchecked"),
      (Test / sourceGenerators) += (Test / avroScalaGenerateSpecific).taskValue)

lazy val cloudflowPekkoUtil =
  Project(id = "cloudflow-pekko-util", base = file("cloudflow-pekko-util"))
    .enablePlugins(GenJavadocPlugin, JavaFormatterPlugin, ScalafmtPlugin)
    .dependsOn(cloudflowPekko, (cloudflowPekkoTestkit % "test->test").classpathDependency)
    .settings(Dependencies.cloudflowPekkoUtil)
    .settings(
      scalaVersion := Dependencies.Scala3,
      scalafmtOnCompile := true,
      javacOptions += "-Xlint:deprecation",
      (Test / sourceGenerators) += (Test / avroScalaGenerateSpecific).taskValue)

lazy val cloudflowPekkoTests =
  Project(id = "cloudflow-pekko-tests", base = file("cloudflow-pekko-tests"))
    .enablePlugins(JavaFormatterPlugin, ScalafmtPlugin)
    .dependsOn(
      cloudflowPekko,
      (cloudflowPekkoTestkit % "test->test").classpathDependency,
      (cloudflowJson % "test").classpathDependency,
      (cloudflowCrd % "test").classpathDependency)
    .settings(Dependencies.cloudflowPekkoTests)
    .settings(
      scalaVersion := Dependencies.Scala3,
      scalafmtOnCompile := true,
      javacOptions += "-Xlint:deprecation",
      inConfig(Test)(sbtprotoc.ProtocPlugin.protobufConfigSettings),
      Compile / PB.targets := Seq(scalapb.gen() -> (Compile / sourceManaged).value / "sproto"),
      Compile / PB.protoSources := Seq(baseDirectory.value / "src/test/protobuf"),
      (Test / sourceGenerators) += (Test / avroScalaGenerateSpecific).taskValue,
      // Several suites here each start a Kafka container. Run in parallel they contend for the machine and time
      // out (PekkoStreamletConsumerGroupSpec did, in every full run, once a fifth container joined); one at a time,
      // they pass.
      Test / parallelExecution := false)

lazy val cloudflowRunner =
  Project(id = "cloudflow-runner", base = file("cloudflow-runner"))
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin)
    .dependsOn(cloudflowStreamlets)
    .settings(
      scalaVersion := Dependencies.Scala3,
      scalafmtOnCompile := true,
      Compile / packageBin / artifactName := { (sv: ScalaVersion, module: ModuleID, artifact: Artifact) =>
        "runner" + "." + artifact.extension
      },
      buildInfoKeys := Seq[BuildInfoKey](
        name,
        version,
        scalaVersion,
        sbtVersion,
        BuildInfoKey.action("buildTime") {
          java.time.Instant.now().toString
        },
        BuildInfoKey.action("buildUser") {
          sys.props.getOrElse("user.name", "unknown")
        }),
      buildInfoPackage := "cloudflow.runner")

lazy val cloudflowLocalRunner =
  Project(id = "cloudflow-localrunner", base = file("cloudflow-localrunner"))
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin)
    .dependsOn(cloudflowStreamlets, cloudflowBlueprint3, cloudflowRunnerConfig)
    .settings(scalaVersion := Dependencies.Scala3, scalafmtOnCompile := true)

lazy val cloudflowCrGenerator =
  Project(id = "cloudflow-cr-generator", base = file("cloudflow-cr-generator"))
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin)
    .dependsOn(cloudflowExtractor, cloudflowBlueprint)
    .settings(Dependencies.cloudflowCrGenerator)
    .settings(
      scalaVersion := Dependencies.Scala212,
      scalafmtOnCompile := true,
      assembly / assemblyMergeStrategy := {
        case PathList("buildinfo", xs @ _*) => MergeStrategy.first
        case x =>
          val oldStrategy = (assembly / assemblyMergeStrategy).value
          oldStrategy(x)
      })

lazy val cloudflowBuildSupport =
  Project(id = "cloudflow-build-support", base = file("cloudflow-build-support"))
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin)
    .dependsOn(cloudflowBlueprint)
    .settings(Dependencies.cloudflowBuildSupport)
    .settings(scalaVersion := Dependencies.Scala212, scalafmtOnCompile := true)

lazy val cloudflowMavenPlugin =
  Project(id = "cloudflow-maven-plugin", base = file("cloudflow-maven-plugin"))
    .enablePlugins(BuildInfoPlugin, ScalafmtPlugin, SbtMavenPlugin)
    .dependsOn(cloudflowCrGenerator, cloudflowBuildSupport)
    .settings(Dependencies.cloudflowMavenPlugin)
    .settings(
      crossPaths := false,
      crossVersion := CrossVersion.disabled,
      scalaVersion := Dependencies.Scala212,
      scalafmtOnCompile := true)

lazy val cloudflowMavenArchetype =
  Project(id = "cloudflow-maven-archetype", base = file("cloudflow-maven-archetype"))
    .settings(
      crossPaths := false,
      crossVersion := CrossVersion.disabled,
      autoScalaLibrary := false,
      scalafmtOnCompile := true)

lazy val root = Project(id = "root", base = file("."))
  .settings(name := "root", publish / skip := true, scalafmtOnCompile := true, crossScalaVersions := Seq())
  .withId("root")
  .enablePlugins(ScalaUnidocPlugin, JavaUnidocPlugin)
  .settings(
    JavaUnidoc / unidoc / unidocAllSources ~= { v =>
      v.map(_.filterNot(f => Common.javadocDisabledFor.exists(f.getAbsolutePath.endsWith(_))))
    },
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
      cloudflowStreamlets,
      cloudflowPekko,
      cloudflowPekkoUtil,
      cloudflowPekkoTestkit),
    JavaUnidoc / unidoc / unidocProjectFilter := (ScalaUnidoc / unidoc / unidocProjectFilter).value)
  .aggregate(
    cloudflowAvro,
    cloudflowJson,
    cloudflowBlueprint,
    cloudflowCli,
    cloudflowConfig,
    cloudflowCrd,
    cloudflowExtractor,
    cloudflowIt,
    cloudflowNewIt,
    cloudflowNewItLibrary,
    cloudflowOperator,
    cloudflowProto,
    cloudflowSbtPlugin,
    cloudflowRunnerConfig,
    cloudflowStreamlets,
    cloudflowPekko,
    cloudflowPekkoTestkit,
    cloudflowPekkoUtil,
    cloudflowPekkoTests,
    cloudflowRunner,
    cloudflowLocalRunner,
    cloudflowCrGenerator,
    cloudflowBuildSupport,
    cloudflowMavenPlugin,
    cloudflowMavenArchetype,
    tooling)
