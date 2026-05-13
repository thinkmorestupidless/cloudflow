import sbt._
import sbt.Keys._

lazy val root =
  Project(id = "root", base = file("."))
    .enablePlugins(ScalafmtPlugin)
    .settings(
      name := "root",
      scalafmtOnCompile := true,
      publish / skip := true,
    )
    .withId("root")
    .settings(commonSettings)
    .aggregate(
      connectedCarExample,
      datamodel,
      akkaConnectedCar
    )

lazy val connectedCarExample = (project in file("./akka-connected-car"))
  .enablePlugins(CloudflowApplicationPlugin)
  .settings(
    commonSettings,
    name := "connected-car-akka-cluster",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.19" % "test"
      )
  )

lazy val datamodel = (project in file("./datamodel"))
  .settings(
    commonSettings,
    Compile / sourceGenerators += (Compile / avroScalaGenerateSpecific).taskValue,
    libraryDependencies += Cloudflow.library.CloudflowAvro
  )

lazy val akkaConnectedCar= (project in file("./akka-connected-car-streamlet"))
  .enablePlugins(CloudflowAkkaPlugin)
  .settings(
    commonSettings,
    name := "akka-connected-car-streamlet",
    libraryDependencies ++= Seq(
      "ch.qos.logback" %  "logback-classic" % "1.2.11",
      "org.scalatest"  %% "scalatest"       % "3.2.19" % "test"
    )
  )
  .dependsOn(datamodel)

lazy val commonSettings = Seq(
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

  Test / console / scalacOptions := (Compile / console / scalacOptions).value

)

ThisBuild / dynverSeparator := "-"
