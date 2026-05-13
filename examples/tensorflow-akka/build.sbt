//tag::docs-projectSetup-example[]
import sbt._
import sbt.Keys._

lazy val tensorflowAkka =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin, ScalafmtPlugin)
    .settings(
//end::docs-projectSetup-example[]
      scalafmtOnCompile := true,
      libraryDependencies ++= Seq(
        Cloudflow.library.CloudflowAvro,
        "ch.qos.logback"         %  "logback-classic"           % "1.2.11",
        "com.typesafe.akka"      %% "akka-http-testkit"         % "10.7.3" % "test",
        "org.tensorflow"         %  "tensorflow"                % "1.15.0",
        "org.tensorflow"         %  "proto"                     % "1.15.0",
        "org.scalatest"          %% "scalatest"                 % "3.2.19" % "test"
//tag::docs-projectName-example[]
      ),
      name := "tensorflow-akka",
//end::docs-projectName-example[]
      organization := "com.lightbend.cloudflow",
      headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),

      scalaVersion := "3.3.5",
      scalacOptions ++= Seq(
        "-encoding", "UTF-8",
        "-deprecation",
        "-feature",
        "-language:_",
        "-unchecked"
      ),
      Compile / sourceGenerators += (Compile / avroScalaGenerateSpecific).taskValue,
      runLocalConfigFile := Some("src/main/resources/local.conf"),
      Test / console / scalacOptions := (Compile / console / scalacOptions).value,
    )

ThisBuild / dynverSeparator := "-"
