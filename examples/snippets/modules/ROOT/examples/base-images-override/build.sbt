import sbt._
import sbt.Keys._

    //tag::docs-projectSetup-example[]
lazy val sampleApp = (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin)
    .settings(
      cloudflowDockerBaseImage := "myRepositoryUrl/myRepositoryPath:adoptopenjdk/openjdk11:alpine",
    //end::docs-projectSetup-example[]
      name := "sample-app",
      organization := "com.lightbend.cloudflow",
      scalaVersion := "3.3.5",
      libraryDependencies ++= Seq(
        "com.typesafe.akka"      %% "akka-http-spray-json"      % "10.7.3",
        "ch.qos.logback"         %  "logback-classic"           % "1.2.11",
        "com.typesafe.akka"      %% "akka-http-testkit"         % "10.7.3" % "test",
        "org.scalatest"          %% "scalatest"                 % "3.2.19" % "test"
      )
    )
