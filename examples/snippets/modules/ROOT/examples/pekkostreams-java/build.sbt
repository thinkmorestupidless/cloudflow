//tag::docs-projectSetup-example[]
import sbt._
import sbt.Keys._

lazy val sensorData =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowPekkoPlugin)
    .settings(
//end::docs-projectSetup-example[]
      libraryDependencies ++= Seq(
        Cloudflow.library.CloudflowAvro,
        "org.apache.pekko"      %% "pekko-http-spray-json"      % "1.4.0",
        "org.apache.pekko"      %% "pekko-http-jackson"         % "1.4.0",
        "ch.qos.logback"         %  "logback-classic"           % "1.2.11",
        "org.apache.pekko"      %% "pekko-http-testkit"         % "1.4.0" % "test",
        "org.scalatest"          %% "scalatest"                 % "3.2.19" % "test"
//tag::docs-projectName-example[]
      ),
      name := "pekkostreams-doc",
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
