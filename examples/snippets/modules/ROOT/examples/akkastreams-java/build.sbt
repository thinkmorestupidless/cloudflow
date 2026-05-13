//tag::docs-projectSetup-example[]
import sbt._
import sbt.Keys._

lazy val sensorData =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
    .settings(
//end::docs-projectSetup-example[]
      libraryDependencies ++= Seq(
        Cloudflow.library.CloudflowAvro,
        "com.typesafe.akka"      %% "akka-http-spray-json"      % "10.7.3",
        "com.typesafe.akka"      %% "akka-http-jackson"         % "10.7.3",
        "ch.qos.logback"         %  "logback-classic"           % "1.2.11",
        "com.typesafe.akka"      %% "akka-http-testkit"         % "10.7.3" % "test",
        "org.scalatest"          %% "scalatest"                 % "3.2.19" % "test"
//tag::docs-projectName-example[]
      ),
      name := "akkastreams-doc",
//end::docs-projectName-example[]
      organization := "com.lightbend.cloudflow",
      headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),

      scalaVersion := "3.3.5",
      javacOptions ++= Seq("-Xlint:deprecation"),
      scalacOptions ++= Seq(
        "-encoding", "UTF-8",
        "-deprecation",
        "-feature",
        "-language:_",
        "-unchecked"
      ),
      javacOptions ++= Seq("-Xlint:deprecation"),
      runLocalConfigFile := Some("src/main/resources/local.conf"),
      libraryDependencies ++= Seq(
        "org.scalatest"          %% "scalatest"                 % "3.2.19"   % "test",
        "junit"                  % "junit"                      % "4.12"     % "test"),

      avroStringType := "String",
      Test / console / scalacOptions := (Compile / console / scalacOptions).value

    )

ThisBuild / dynverSeparator := "-"
