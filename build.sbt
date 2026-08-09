import Dependencies._
import org.scalajs.linker.interface.ModuleKind

// TEMPLATE SETTING: the OneDrive-synced folder holding this deployment's secrets, outside the repository. Its
// path is OS-dependent because OneDrive mounts under a different root on each platform.
val secretsDir = file(
  if (sys.props("os.name").toLowerCase.contains("mac"))
    "/Users/raz/Library/CloudStorage/OneDrive-Personal/code/@secrets/webapptemplate"
  else
    "C:/Users/razva/OneDrive/code/@secrets/webapptemplate"
)

// The Google OAuth "Web application" JSON downloaded from Google Cloud; must contain web.client_id and
// web.client_secret.
val oauthConfigFile = secretsDir / "oauth.config.json"

// A single-line plain-text password gating routes declared `@Route(adminPwd = true)`, e.g. /debug.
val adminPasswordFile = secretsDir / "admin.pwd"

// gcloud's own well-known Application Default Credentials location, baked into the Docker image (only) so
// `docker run` can reach Firestore/Sheets outside a GCP platform's own workload identity. Generate it with
// `gcloud auth application-default login`. Optional: if missing, the Docker image is still built, but the
// container will need credentials supplied another way (e.g. running on Cloud Run/GKE/GCE, or a mounted file).
val adcFile = file(
  if (sys.props("os.name").toLowerCase.contains("win"))
    s"${sys.env.getOrElse("APPDATA", "")}/gcloud/application_default_credentials.json"
  else
    s"${sys.props("user.home")}/.config/gcloud/application_default_credentials.json"
)

// TEMPLATE SETTING: the target platform for the Docker image, independent of the machine running `sbt artifact`
// (e.g. building on Apple Silicon for an amd64 deployment host). BuildKit cross-builds via emulation as needed.
val dockerPlatform = "linux/amd64"

lazy val deploymentArtifact = taskKey[Unit]("Build the backend's Docker image")

addCommandAlias("artifact", "deploymentArtifact")

ThisBuild / scalaVersion     := "3.8.4"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.example"
ThisBuild / organizationName := "example"

lazy val frontend = (project in file("frontend"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name                            := "webapptemplate-frontend",
    coverageEnabled                 := false,
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.NoModule)),
    libraryDependencies ++= Seq(
      "com.raquo"    %%% "laminar"     % laminarVersion,
      "org.scala-js" %%% "scalajs-dom" % scalajsDomVersion
    )
  )

// Copies the linked Scala.js output into the backend's classpath under `web/` to facilitate packaging and local running
lazy val frontendAssets = Def.task {
  val linkedDir = (frontend / Compile / fastLinkJSOutput).value
  val outDir    = (Compile / resourceManaged).value / "web"
  IO.createDirectory(outDir)
  linkedDir.listFiles().filter(_.isFile).toSeq.map { src =>
    val dest = outDir / src.getName
    IO.copyFile(src, dest)
    dest
  }
}

// Parses a `KEY=value` env file in the format shared by prod.env, test.env, and the runApp launcher, so any such
// file can seed `sbt run`'s forked process without deployment identity living in the source.
def parseEnvFile(file: File): Map[String, String] =
  if (!file.isFile) Map.empty
  else
    IO.readLines(file)
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .flatMap { line =>
        line.split("=", 2) match {
          case Array(key, value) => Some(key.trim -> value.trim)
          case _                 => None
        }
      }
      .toMap

lazy val backend = (project in file("backend"))
  .settings(
    name := "webapptemplate-backend",
    libraryDependencies ++= Seq(
      classGraph, zioHttp, zioLogging, firestore, firestoreAdmin, googleApiClient, gson, munit % Test
    ),
    Compile / resourceGenerators += frontendAssets.taskValue,
    run / fork         := true,
    run / connectInput := true,
    // Some networks hand out AAAA (IPv6) records for googleapis.com without actually routing IPv6, which makes
    // outbound Sheets/Drive calls fail with NoRouteToHostException; prefer IPv4 to avoid that.
    run / javaOptions += "-Djava.net.preferIPv4Stack=true",
    run / envVars ++= parseEnvFile((Compile / resourceDirectory).value / "test.env") ++
      OAuthBuild.configEnv(oauthConfigFile) ++ AdminBuild.configEnv(adminPasswordFile)
  )

lazy val root = (project in file("."))
  .aggregate(frontend, backend)
  .settings(
    name            := "webapptemplate",
    coverageEnabled := false,
    publish / skip  := true,
    run / aggregate := false,
    Compile / run   := (backend / Compile / run).evaluated,
    deploymentArtifact := Def.taskDyn {
      clean.value
      Def.task {
        val log         = streams.value.log
        val appJar      = (backend / Compile / packageBin).value
        val runtimeJars = (backend / Runtime / dependencyClasspath).value.map(_.data).filter(_.isFile)
        val resources   = (backend / Compile / resourceDirectory).value
        val outputDir   = (backend / Compile / target).value

        val duplicateJarNames = runtimeJars.groupBy(_.getName).collect {
          case (jarName, jars) if jars.size > 1 => jarName
        }
        if (duplicateJarNames.nonEmpty)
          sys.error(s"Runtime dependency filename collision: ${duplicateJarNames.mkString(", ")}")

        val secretEnv = OAuthBuild.configEnv(oauthConfigFile) ++ AdminBuild.configEnv(adminPasswordFile)

        // Stage the Docker build context: app.jar, lib/*.jar, a secret-bearing prod.env, runApp (the image's
        // entrypoint), the Dockerfile itself, and (if available) local ADC for testing outside a GCP platform's
        // own workload identity.
        val dockerDir = outputDir / "docker"
        IO.delete(dockerDir)
        IO.createDirectory(dockerDir / "lib")
        IO.copyFile(appJar, dockerDir / "app.jar")
        runtimeJars.foreach(jar => IO.copyFile(jar, dockerDir / "lib" / jar.getName))

        val sourceEnv = resources / "prod.env"
        if (!sourceEnv.isFile) sys.error(s"Missing packaging resource: ${sourceEnv.getAbsolutePath}")
        val envLines = IO.readLines(sourceEnv) ++ OAuthBuild.envLines(secretEnv)
        // Explicit "\n" rather than IO.writeLines (which joins with the platform line separator, i.e. "\r\n"
        // when this task runs on Windows): prod.env is `.`-sourced by the POSIX runApp script regardless of
        // which OS built it, and a CRLF-corrupted value there isn't caught by this app's own defensive
        // trimming for every variable (PORT notably isn't trimmed before being parsed as an integer).
        IO.write(dockerDir / "prod.env", envLines.map(_ + "\n").mkString)

        Seq("runApp", "Dockerfile").foreach { fileName =>
          val source = resources / fileName
          if (!source.isFile) sys.error(s"Missing packaging resource: ${source.getAbsolutePath}")
          IO.copyFile(source, dockerDir / fileName)
        }

        if (adcFile.isFile) IO.copyFile(adcFile, dockerDir / "application_default_credentials.json")
        else
          log.warn(
            s"No Application Default Credentials found at ${adcFile.getAbsolutePath}; building the Docker image " +
              "without them. Run `gcloud auth application-default login` first, or supply credentials to the " +
              "container another way (e.g. GCP workload identity on Cloud Run/GKE/GCE)."
          )

        val imageTag = s"${name.value}:${version.value}"
        val dockerBuildArgs = Seq(
          "docker", "build",
          "--platform", dockerPlatform,
          "-t", imageTag,
          "-t", s"${name.value}:latest",
          "."
        )
        val exitCode = sys.process.Process(dockerBuildArgs, dockerDir).!
        if (exitCode != 0) sys.error(s"docker build failed for $imageTag (exit code $exitCode)")
        log.success(s"Built Docker image for $dockerPlatform: $imageTag (and ${name.value}:latest)")
      }
    }.value
  )
