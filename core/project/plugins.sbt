addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.10.4")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.12.0")
addSbtPlugin("com.lightbend.sbt" % "sbt-java-formatter" % "0.8.0")
// discipline
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.10.0")

// publishing
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.2.1")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.11.3")

addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.5.0")

libraryDependencies ++= Seq(
  "org.codehaus.plexus" % "plexus-container-default" % "2.1.1",
  "org.codehaus.plexus" % "plexus-archiver" % "4.2.7")

addSbtPlugin("com.julianpeeters" % "sbt-avrohugger" % "2.8.0")
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.7")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.11"

addSbtPlugin("com.lucidchart" % "sbt-cross" % "4.0")
// sbt-assembly 2.x: assemblyMergeStrategy key is unchanged but the
// PathList extractor is now in sbtassembly package (was already the case).
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.2.0")
