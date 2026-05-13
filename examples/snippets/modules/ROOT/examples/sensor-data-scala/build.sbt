//tag::get-started[]
//tag::local-conf[]
lazy val sensorData =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowAkkaPlugin)
    .settings(
      scalaVersion := "3.3.5",
      runLocalConfigFile := Some("src/main/resources/local.conf"), //<1>
      runLocalLog4jConfigFile := Some("src/main/resources/log4j.xml"), //<2>
      name := "sensor-data-scala",
//end::local-conf[]      

      libraryDependencies ++= Seq(
        Cloudflow.library.CloudflowAvro,
        "com.typesafe.akka"      %% "akka-http-spray-json"      % "10.7.3",
        "ch.qos.logback"         %  "logback-classic"           % "1.2.11",
        "com.typesafe.akka"      %% "akka-http-testkit"         % "10.7.3" % "test",
        "org.scalatest"          %% "scalatest"                 % "3.2.19" % "test"
      )
    )
//end::get-started[]
    .enablePlugins(ScalafmtPlugin)
    .settings(
      scalafmtOnCompile := true,

      organization := "com.lightbend.cloudflow",
      headerLicense := Some(HeaderLicense.ALv2("(C) 2016-2020", "Lightbend Inc. <https://www.lightbend.com>")),

      scalacOptions ++= Seq(
        "-encoding", "UTF-8",
        "-deprecation",
        "-feature",
        "-language:_",
        "-unchecked"
      ),

      Compile / sourceGenerators += (Compile / avroScalaGenerateSpecific).taskValue,
      Test / console / scalacOptions := (Compile / console / scalacOptions).value
    )

ThisBuild / dynverSeparator := "-"
