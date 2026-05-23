addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.2.1")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")

resolvers ++= sys.env
  .get("LIGHTBEND_COMMERCIAL_TOKEN")
  .toSeq
  .flatMap { token =>
    Seq(
      "akka-secure-mvn".at(s"https://repo.akka.io/$token/secure"),
      Resolver.url("akka-secure-ivy", url(s"https://repo.akka.io/$token/secure"))(Resolver.ivyStylePatterns)
    )
  }

addSbtPlugin("com.lightbend.akka.grpc" % "sbt-akka-grpc" % "2.5.10")

libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % "always"
