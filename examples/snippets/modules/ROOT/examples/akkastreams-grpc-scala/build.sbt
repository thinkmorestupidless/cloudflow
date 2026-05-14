import sbt._
import sbt.Keys._

enablePlugins(
  CloudflowApplicationPlugin,
  CloudflowAkkaPlugin,
  AkkaGrpcPlugin
)

scalaVersion := "3.3.5"

akkaGrpcGeneratedLanguages := Seq(AkkaGrpc.Scala)

libraryDependencies ++= Seq(
  Cloudflow.library.CloudflowProto,
)

ThisBuild / dynverSeparator := "-"

// sbt-akka-grpc 2.5.x (via twirl-api) and sbt-native-packager pull in incompatible scala-xml versions
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
