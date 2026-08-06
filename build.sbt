import Dependencies._
import org.scalajs.linker.interface.ModuleKind

// TEMPLATE SETTING: point this at the Google OAuth "Web application" JSON downloaded from Google Cloud.
// The file must contain web.client_id and web.client_secret. Keep it outside this repository.
val oauthConfigFile = file("C:/Users/razva/OneDrive/code/@secrets/webapptemplate/oauth.config.json")

lazy val deploymentArtifact = taskKey[File]("Build a self-contained backend deployment ZIP")

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

// Parses a `KEY=value` env file (as used by runApp/runApp.bat) so the same file can seed `sbt run`'s forked
// process, keeping deployment identity out of the source and out of manual local setup alike.
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
    libraryDependencies ++= Seq(classGraph, zioHttp, zioLogging, firestore, firestoreAdmin, googleApiClient, munit % Test),
    Compile / resourceGenerators += frontendAssets.taskValue,
    run / fork         := true,
    run / connectInput := true,
    run / envVars ++= parseEnvFile((Compile / resourceDirectory).value / "prod.env") ++ OAuthBuild.configEnv(oauthConfigFile)
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
        val appJar      = (backend / Compile / packageBin).value
        val runtimeJars = (backend / Runtime / dependencyClasspath).value.map(_.data).filter(_.isFile)
        val resources   = (backend / Compile / resourceDirectory).value
        val outputDir   = (backend / Compile / target).value
        val stagingDir  = outputDir / "artifact"
        val artifactZip = outputDir / s"${name.value}-${version.value}.zip"

        IO.delete(stagingDir)
        IO.createDirectory(stagingDir / "lib")
        IO.copyFile(appJar, stagingDir / "app.jar")

        val duplicateJarNames = runtimeJars.groupBy(_.getName).collect {
          case (jarName, jars) if jars.size > 1 => jarName
        }
        if (duplicateJarNames.nonEmpty)
          sys.error(s"Runtime dependency filename collision: ${duplicateJarNames.mkString(", ")}")

        runtimeJars.foreach(jar => IO.copyFile(jar, stagingDir / "lib" / jar.getName))

        Seq("runApp", "runApp.bat").foreach { fileName =>
          val source = resources / fileName
          if (!source.isFile) sys.error(s"Missing packaging resource: ${source.getAbsolutePath}")
          IO.copyFile(source, stagingDir / fileName)
        }

        val sourceEnv = resources / "prod.env"
        if (!sourceEnv.isFile) sys.error(s"Missing packaging resource: ${sourceEnv.getAbsolutePath}")
        IO.writeLines(
          stagingDir / "prod.env",
          IO.readLines(sourceEnv) ++ OAuthBuild.envLines(OAuthBuild.configEnv(oauthConfigFile))
        )

        IO.delete(artifactZip)
        IO.zip(Path.allSubpaths(stagingDir).toSeq, artifactZip, None)
        streams.value.log.success(s"Created deployment artifact: ${artifactZip.getAbsolutePath}")
        artifactZip
      }
    }.value
  )
