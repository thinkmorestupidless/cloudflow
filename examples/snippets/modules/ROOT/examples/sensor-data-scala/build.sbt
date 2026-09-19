//tag::get-started[]
//tag::local-conf[]
lazy val sensorData =  (project in file("."))
    .enablePlugins(CloudflowApplicationPlugin, CloudflowPekkoPlugin)
    .settings(
      scalaVersion := "3.3.5",
      runLocalConfigFile := Some("src/main/resources/local.conf"), //<1>
      runLocalLog4jConfigFile := Some("src/main/resources/log4j.xml"), //<2>
      name := "sensor-data-scala",
//end::local-conf[]      

      libraryDependencies ++= Seq(
        Cloudflow.library.CloudflowAvro,
        "org.apache.pekko"      %% "pekko-http-spray-json"      % "1.4.0",
        "ch.qos.logback"         %  "logback-classic"           % "1.2.11",
        "org.apache.pekko"      %% "pekko-http-testkit"         % "1.4.0" % "test",
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
