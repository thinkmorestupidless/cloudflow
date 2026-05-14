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
)

ThisBuild / dynverSeparator := "-"

