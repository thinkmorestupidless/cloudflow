import sbt._
import sbt.Keys._

enablePlugins(
  CloudflowApplicationPlugin,
  CloudflowAkkaPlugin,
  AkkaGrpcPlugin
)

scalaVersion := "3.3.5"

akkaGrpcGeneratedLanguages := Seq(AkkaGrpc.Java)
libraryDependencies ++= Seq(
  Cloudflow.library.CloudflowProto,
  "com.typesafe.akka" %% "akka-http" % "10.7.3"
)

ThisBuild / dynverSeparator := "-"
