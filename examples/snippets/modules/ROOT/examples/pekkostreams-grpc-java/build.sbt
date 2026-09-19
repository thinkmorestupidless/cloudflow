import sbt._
import sbt.Keys._

enablePlugins(
  CloudflowApplicationPlugin,
  CloudflowPekkoPlugin,
  PekkoGrpcPlugin
)

scalaVersion := "3.3.5"

pekkoGrpcGeneratedLanguages := Seq(PekkoGrpc.Java)
libraryDependencies ++= Seq(
  Cloudflow.library.CloudflowProto,
)

ThisBuild / dynverSeparator := "-"

