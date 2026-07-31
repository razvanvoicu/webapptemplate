import sbt._

object Dependencies {
  lazy val classGraph = "io.github.classgraph" % "classgraph"    % "4.8.181"
  lazy val zioHttp    = "dev.zio"       %% "zio-http"    % "3.11.3"
  lazy val zioLogging = "dev.zio"       %% "zio-logging" % "2.5.3"
  lazy val munit      = "org.scalameta" %% "munit"       % "1.3.4"

  // Scala.js artifacts are declared with `%%%` in build.sbt, which needs a
  // settings scope, so only their versions live here.
  val laminarVersion    = "17.2.1"
  val scalajsDomVersion = "2.8.1"
}
