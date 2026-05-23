import sbt._
import sbt.Keys._

lazy val templateJavaProject = (project in file("."))
    .enablePlugins(CloudflowAkkaPlugin, CloudflowApplicationPlugin, ScalafmtPlugin)
    .settings(
      scalafmtOnCompile := true,
      libraryDependencies ++= Seq(
        Cloudflow.library.CloudflowAvro,
	      "ch.qos.logback" % "logback-classic" % "1.2.11",
        "org.scalatest" %% "scalatest"       % "3.2.19" % "test"
      ),

      name := "template-java",
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
      runLocalConfigFile := Some("src/main/resources/local.conf"),
      avroStringType := "String",
      Test / console / scalacOptions := (Compile / console / scalacOptions).value
    )

ThisBuild / dynverSeparator := "-"
