lazy val helloWorld = (project in file("."))
  .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
  .settings(
    scalaVersion := "3.3.5",
    name := "hello-world",
    version := "0.0.1",
    cloudflowDockerBaseImage := "eclipse-temurin:21-jre-alpine",
    libraryDependencies ++= Seq("ch.qos.logback" % "logback-classic" % "1.2.11"))
