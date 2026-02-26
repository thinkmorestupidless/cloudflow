import sbt._

/** Reads Lightbend commercial credentials from the environment or a local gitignored file.
 *
 * Resolution order for each credential:
 *   1. Environment variable (used in CI)
 *   2. Gitignored file in core/ (used for local development)
 *   3. sys.error — SBT fails to load
 *
 * Local dev setup:
 *   echo "<token>"  > core/.lightbend-token
 *   echo "<key>"    > core/.akka-license-key
 */
object LightbendCredentials {

  private def readFile(f: File): Option[String] = {
    if (f.exists()) {
      val content = IO.read(f).trim
      if (content.nonEmpty) Some(content) else None
    } else None
  }

  /** Lightbend commercial token — embedded in the artifact repository URL. */
  lazy val token: String =
    sys.env
      .get("LIGHTBEND_COMMERCIAL_TOKEN")
      .orElse(readFile(file(".lightbend-token")))
      .getOrElse(sys.error(
        "\n[error] Lightbend commercial token not found.\n" +
        "[error] Provide it via:\n" +
        "[error]   1. Environment variable:  export LIGHTBEND_COMMERCIAL_TOKEN=<token>\n" +
        "[error]   2. Local file:             echo '<token>' > core/.lightbend-token\n"
      ))

  /** Akka commercial license key — required at runtime for Akka >= 2.7. */
  lazy val akkaLicenseKey: String =
    sys.env
      .get("AKKA_LICENSE_KEY")
      .orElse(readFile(file(".akka-license-key")))
      .getOrElse(sys.error(
        "\n[error] Akka license key not found.\n" +
        "[error] Provide it via:\n" +
        "[error]   1. Environment variable:  export AKKA_LICENSE_KEY=<key>\n" +
        "[error]   2. Local file:             echo '<key>' > core/.akka-license-key\n"
      ))

  /** Maven and Ivy resolvers pointing at the Lightbend commercial repository. */
  def lightbendResolvers: Seq[Resolver] = Seq(
    "akka-secure-mvn" at s"https://repo.akka.io/$token/secure",
    Resolver.url("akka-secure-ivy", url(s"https://repo.akka.io/$token/secure"))(
      Resolver.ivyStylePatterns
    )
  )
}
