import Dependencies._
import org.scalajs.linker.interface.ModuleKind

// Local pointer files keep machine-specific secret locations outside source control. OAuth configuration is
// compulsory; Debug remains opt-in. Each pointer must be the first line of exactly one regular file directly
// under .local. Relative target paths are resolved from the repository root.
val repositoryDir          = file(".").getCanonicalFile
val localConfigDir         = repositoryDir / ".local"
val oauthConfigPathPrefix  = "OAUTHCONFIGPATH="
val adminPasswordPathPrefix = "ADMINPASSWORDPATH="

// The Google OAuth "Web application" JSON downloaded from Google Cloud; must contain web.client_id and
// web.client_secret. Merely loading the build fails if its compulsory .local pointer or target file is absent.
val oauthConfigFile: File =
  LocalConfigBuild.requiredPath(localConfigDir, oauthConfigPathPrefix, repositoryDir)

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

// TEMPLATE SETTING: where Chrome lives, for `e2etest/launchTestBrowser`'s visible, remote-debuggable instance —
// used to sign in to Google manually once and reuse that session in an authenticated E2E run, since Google
// blocks WebDriver-controlled browsers from driving its login form directly.
val chromeExecutable = file(
  if (sys.props("os.name").toLowerCase.contains("mac"))
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  else if (sys.props("os.name").toLowerCase.contains("win"))
    s"${sys.env.getOrElse("PROGRAMFILES", "C:/Program Files")}/Google/Chrome/Application/chrome.exe"
  else
    "/usr/bin/google-chrome"
)

// The Chrome DevTools Protocol port `launchTestBrowser` opens on 127.0.0.1, for a later authenticated E2E suite
// to attach to instead of launching its own (signed-out) browser.
val testBrowserDebugPort = 9222

lazy val deploymentArtifact = taskKey[Unit]("Build the backend's Docker image")

lazy val launchTestBrowser = taskKey[Unit](
  "Launch the Firestore emulator, the coverage-instrumented backend, and a visible, remote-debuggable Chrome " +
    "(opened to the backend's home page) — all left running so you can sign in to Google manually and have an " +
    "authenticated E2E run later reuse that session"
)

lazy val testAuthenticated = taskKey[Unit](
  "Run the authenticated E2E suite against the already-running backend and test browser started by " +
    "launchTestBrowser (after you've signed in manually there), instead of launching fresh, signed-out ones"
)

lazy val readmeToHtml = taskKey[Unit]("Render README.rst to target/README.html via docutils")
lazy val localAdminPasswordFile = taskKey[Option[File]](
  "Resolve the optional ADMINPASSWORDPATH pointer from the current contents of .local"
)
lazy val optionalDebugPluginJar = taskKey[Option[File]](
  "Build the Debug plugin JAR when ADMINPASSWORDPATH is configured under .local"
)

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

// True if something is already accepting connections on host:port. Used both so the e2etest suite doesn't
// silently test against a stray leftover process on the backend's port, and so `launchTestBrowser` can detect
// an already-running Chrome instance instead of launching a duplicate.
def isPortOpen(host: String, port: Int): Boolean =
  try {
    val socket = new java.net.Socket()
    try { socket.connect(new java.net.InetSocketAddress(host, port), 500); true }
    finally socket.close()
  } catch { case _: Throwable => false }

// Polls isPortOpen until it succeeds or timeoutMillis elapses.
def waitForPortOpen(host: String, port: Int, timeoutMillis: Long): Boolean = {
  val deadline = System.currentTimeMillis() + timeoutMillis
  while (!isPortOpen(host, port) && System.currentTimeMillis() < deadline) Thread.sleep(300)
  isPortOpen(host, port)
}

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
    localAdminPasswordFile :=
      LocalConfigBuild.optionalPath(localConfigDir, adminPasswordPathPrefix, repositoryDir),
    run / envVars ++= parseEnvFile((Compile / resourceDirectory).value / "test.env") ++
      OAuthBuild.configEnv(oauthConfigFile) ++
      localAdminPasswordFile.value.fold(Map.empty[String, String])(AdminBuild.configEnv),
    optionalDebugPluginJar := Def.taskDyn {
      if (localAdminPasswordFile.value.nonEmpty)
        Def.task[Option[File]](Some((LocalProject("debugPlugin") / Compile / packageBin).value))
      else Def.task[Option[File]](None)
    }.value,
    Runtime / unmanagedClasspath ++= optionalDebugPluginJar.value.toSeq.map(Attributed.blank),
    Test / unmanagedClasspath ++= optionalDebugPluginJar.value.toSeq.map(Attributed.blank)
  )

/** Optional diagnostic plugin. It compiles against the backend SPI and produces a separate JAR. Runtime and
  * test tasks add it to backend classpaths only when the current .local contents configure ADMINPASSWORDPATH.
  */
lazy val debugPlugin = (project in file("debug-plugin"))
  .dependsOn(backend % "provided->compile")
  .settings(
    name := "webapptemplate-debug-plugin",
    libraryDependencies += munit % Test
  )

// End-to-end Selenium suite. Its `test` task doesn't just run tests: it first checks Chrome/Selenium are
// actually launchable, then starts the real backend as a background process compiled with scoverage
// instrumentation (via a nested `sbt ... coverage run`, mirroring the coverage workflow already documented
// below), so the HTTP traffic these tests generate counts toward backend coverage alongside the unit suite's
// own. `coverageReport` is left as a separate, manual step (see Tests and coverage) rather than triggered here,
// so a unit-test run and an e2etest run can both contribute to one combined report without either one
// clobbering the other's data.
lazy val e2etest = (project in file("e2etest"))
  .settings(
    name            := "webapptemplate-e2etest",
    coverageEnabled := false,
    libraryDependencies ++= Seq(selenium % Test, munit % Test),
    launchTestBrowser := {
      val log      = streams.value.log
      val repoRoot = (ThisBuild / baseDirectory).value

      val testEnv = parseEnvFile((backend / Compile / resourceDirectory).value / "test.env")
      val (emulatorHost, emulatorPort) =
        testEnv.getOrElse(
          "FIRESTORE_EMULATOR_HOST",
          sys.error("backend/src/main/resources/test.env is missing FIRESTORE_EMULATOR_HOST")
        ).split(":", 2) match {
          case Array(host, port) => (host, port.toInt)
          case _                 => sys.error(s"Malformed FIRESTORE_EMULATOR_HOST in test.env")
        }
      val projectId =
        testEnv.getOrElse("GCP_PROJECT_ID", sys.error("backend/src/main/resources/test.env is missing GCP_PROJECT_ID"))

      // 1. The Firestore emulator: sessions live there (see SessionStore), and since it's in-memory only, it
      // has to keep running continuously from the manual login below through to a later authenticated test run
      // — restarting it wipes the session you're about to create.
      if (isPortOpen(emulatorHost, emulatorPort))
        log.info(s"Firestore emulator already listening on $emulatorHost:$emulatorPort; reusing it.")
      else {
        log.info(s"Starting the Firestore emulator (project $projectId) in the background...")
        val emulatorLog = (Test / target).value / "test-firestore-emulator.log"
        try
          new java.lang.ProcessBuilder("firebase", "emulators:start", "--only", "firestore", s"--project=$projectId")
            .directory(repoRoot)
            .redirectErrorStream(true)
            .redirectOutput(emulatorLog)
            .start()
        catch {
          case _: java.io.IOException =>
            sys.error("firebase CLI not found on PATH; install it with `npm install -g firebase-tools` first.")
        }
        if (!waitForPortOpen(emulatorHost, emulatorPort, 60000))
          sys.error(s"Firestore emulator did not open $emulatorHost:$emulatorPort within 60s (see $emulatorLog).")
      }

      // 2. The backend, coverage-instrumented and pointed at that emulator via test.env, left running (unlike
      // e2etest/test's own backend, which it starts and tears down itself) so the session created by the
      // manual login below is still there for a later authenticated test run to reuse.
      if (isPortOpen("127.0.0.1", 8888))
        log.info("Backend already listening on 127.0.0.1:8888; reusing it.")
      else {
        log.info("Starting the backend in the background with scoverage coverage enabled...")
        val backendLog = (Test / target).value / "test-backend.log"
        new java.lang.ProcessBuilder("sbt", "project backend", "coverage", "run")
          .directory(repoRoot)
          .redirectErrorStream(true)
          .redirectOutput(backendLog)
          .start()
        if (!waitForPortOpen("127.0.0.1", 8888, 90000))
          sys.error(s"Backend did not become ready within 90s (see $backendLog).")
      }

      // 3. A visible, remote-debuggable Chrome, opened to the backend's home page and ready for you to click
      // "Login with Google" yourself — Google blocks WebDriver-controlled browsers from driving its login form.
      if (isPortOpen("127.0.0.1", testBrowserDebugPort))
        log.info(s"Something is already listening on 127.0.0.1:$testBrowserDebugPort; reusing it as the test browser.")
      else {
        if (!chromeExecutable.isFile)
          sys.error(
            s"No Chrome executable found at ${chromeExecutable.getAbsolutePath}; update chromeExecutable in " +
              "build.sbt for your install location."
          )
        // A dedicated profile dir (not your everyday one — Chrome refuses to open the same profile twice, and
        // leaving remote debugging enabled on your daily-driver profile would let anything on this machine
        // attach to it) that persists under target/ so the signed-in session survives across separate
        // launchTestBrowser invocations, not just within one.
        val profileDir = (Test / target).value / "test-chrome-profile"
        IO.createDirectory(profileDir)
        val chromeLog = (Test / target).value / "test-chrome.log"
        new java.lang.ProcessBuilder(
          chromeExecutable.getAbsolutePath,
          s"--remote-debugging-port=$testBrowserDebugPort",
          s"--user-data-dir=${profileDir.getAbsolutePath}",
          "--no-first-run",
          "--no-default-browser-check"
        ).redirectErrorStream(true).redirectOutput(chromeLog).start()
        if (!waitForPortOpen("127.0.0.1", testBrowserDebugPort, 15000))
          sys.error(s"Chrome did not open its remote debugging port within 15s (see $chromeLog).")
      }

      log.info("Opening the backend's home page in the test browser...")
      // "localhost", not "127.0.0.1": the backend derives its OAuth redirect_uri from the request's Host header
      // verbatim, and the Google Cloud OAuth client is registered against http://localhost:8888/auth/callback
      // (see README's OAuth setup) — 127.0.0.1 would produce a redirect_uri Google doesn't recognize.
      val newTabRequest = java.net.http.HttpRequest
        .newBuilder(java.net.URI.create(s"http://127.0.0.1:$testBrowserDebugPort/json/new?http://localhost:8888/"))
        .PUT(java.net.http.HttpRequest.BodyPublishers.noBody())
        .build()
      java.net.http.HttpClient.newHttpClient()
        .send(newTabRequest, java.net.http.HttpResponse.BodyHandlers.discarding())

      log.info(
        """|The Firestore emulator, backend, and test browser are all up, and will keep running after this task
           |exits (none of them are children of this sbt session).
           |
           |  1. Switch to the test browser window and sign in with Google as you normally would.
           |  2. Leave that window, the backend, and the emulator all running — stopping any of them ends the
           |     session.
           |  3. Come back here once you're signed in.
           |""".stripMargin
      )
    },
    testAuthenticated := Def.taskDyn {
      val log = streams.value.log
      if (!isPortOpen("127.0.0.1", 8888))
        sys.error("No backend is listening on 127.0.0.1:8888; run `sbt e2etest/launchTestBrowser` first.")
      if (!isPortOpen("127.0.0.1", testBrowserDebugPort))
        sys.error(
          s"No test browser is listening on 127.0.0.1:$testBrowserDebugPort; run `sbt e2etest/launchTestBrowser` " +
            "first."
        )
      log.info("Running the authenticated E2E suite against the already-running backend and test browser...")
      (Test / testOnly).toTask(" sgrv.e2e.SampleSpreadsheetE2ESuite")
    }.value,
    Test / test := Def.taskDyn {
      val log      = streams.value.log
      val repoRoot = (ThisBuild / baseDirectory).value

      log.info("Checking that Selenium can launch Chrome...")
      (Test / runMain).toTask(" sgrv.e2e.SeleniumCheck").value

      // A stray already-running process on this port (e.g. a Docker container left over from manual testing)
      // would otherwise make the readiness poll below pass instantly against THAT process instead of the
      // freshly coverage-instrumented one this task is about to start — silently testing the wrong instance
      // and recording zero coverage, while still reporting green.
      if (isPortOpen("127.0.0.1", 8888))
        sys.error(
          "Port 8888 is already in use by something else (check `docker ps` / `lsof -i :8888`); stop it first " +
            "so the E2E suite can be sure it's talking to the coverage-instrumented backend this task starts."
        )

      log.info("Starting the backend in the background with scoverage coverage enabled...")
      val backendLog = (Test / target).value / "e2e-backend.log"
      val backendProcess = new java.lang.ProcessBuilder("sbt", "project backend", "coverage", "run")
        .directory(repoRoot)
        .redirectErrorStream(true)
        .redirectOutput(backendLog)
        .start()

      // `run / fork := true` means this nested sbt process itself forks a *child* JVM to run sgrv.be.Main;
      // stopping just the sbt process (the direct child of the ProcessBuilder above) leaves that grandchild
      // running and still bound to the port. Walk the whole descendant tree instead.
      def stopBackendTree(): Unit = {
        val handle = backendProcess.toHandle
        handle.descendants().forEach(p => p.destroy())
        backendProcess.destroy()
        val exitedCleanly =
          backendProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) &&
            !handle.descendants().anyMatch(_.isAlive)
        if (!exitedCleanly) {
          handle.descendants().forEach(p => p.destroyForcibly())
          backendProcess.destroyForcibly()
        }
      }

      val ready = {
        val client  = java.net.http.HttpClient.newHttpClient()
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:8888/")).GET().build()
        val deadline = System.currentTimeMillis() + 90000
        var upAt: Option[Long] = None
        while (upAt.isEmpty && System.currentTimeMillis() < deadline && backendProcess.isAlive) {
          try {
            client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
            upAt = Some(System.currentTimeMillis())
          } catch { case _: Throwable => Thread.sleep(500) }
        }
        upAt.isDefined
      }
      if (!ready) {
        stopBackendTree()
        sys.error(
          s"Backend did not become ready within 90s (see $backendLog for its output); " +
            "not running the E2E suite."
        )
      }
      log.info("Backend is up; running the E2E suite...")

      Def.task {
        try {
          val output = (Test / executeTests).value
          if (output.overall != TestResult.Passed) sys.error(s"E2E tests: ${output.overall}")
        } finally {
          log.info("Stopping the backend...")
          stopBackendTree()
        }
      }
    }.value
  )

lazy val root = {
  Project(id = "root", base = file(".")).aggregate(frontend, backend).settings(
    name            := "webapptemplate",
    coverageEnabled := false,
    publish / skip  := true,
    run / aggregate := false,
    Compile / run   := (backend / Compile / run).evaluated,
    Test / test / aggregate := false,
    Test / test := Def.taskDyn {
      if ((backend / localAdminPasswordFile).value.nonEmpty)
        Def.task {
          (frontend / Test / test).value
          (backend / Test / test).value
          (debugPlugin / Test / test).value
        }
      else
        Def.task {
          (frontend / Test / test).value
          (backend / Test / test).value
        }
    }.value,
    clean / aggregate := false,
    clean := {
      (frontend / clean).value
      (backend / clean).value
      (debugPlugin / clean).value
      IO.delete((Compile / target).value)
    },
    readmeToHtml := {
      val log       = streams.value.log
      val repoRoot  = (ThisBuild / baseDirectory).value
      val outputDir = (Compile / target).value
      IO.createDirectory(outputDir)
      val exitCode =
        try
          sys.process.Process(Seq("python3", "-m", "docutils", "README.rst", "target/README.html"), repoRoot).!
        catch {
          case _: java.io.IOException =>
            sys.error("python3 not found on PATH, or its docutils module isn't installed (`pip install docutils`).")
        }
      if (exitCode != 0) sys.error(s"docutils failed to render README.rst (exit code $exitCode)")
      log.success(s"Rendered README.rst to ${outputDir / "README.html"}")
    },
    deploymentArtifact := Def.taskDyn {
      clean.value
      Def.task {
        val log         = streams.value.log
        val appJar      = (backend / Compile / packageBin).value
        val debugJars    = (backend / optionalDebugPluginJar).value.toSeq
        val runtimeJars = (
          (backend / Runtime / dependencyClasspath).value.map(_.data).filter(_.isFile) ++ debugJars
        ).distinct
        val resources   = (backend / Compile / resourceDirectory).value
        val outputDir   = (backend / Compile / target).value

        val duplicateJarNames = runtimeJars.groupBy(_.getName).collect {
          case (jarName, jars) if jars.size > 1 => jarName
        }
        if (duplicateJarNames.nonEmpty)
          sys.error(s"Runtime dependency filename collision: ${duplicateJarNames.mkString(", ")}")

        val secretEnv = OAuthBuild.configEnv(oauthConfigFile) ++
          (backend / localAdminPasswordFile).value.fold(Map.empty[String, String])(AdminBuild.configEnv)

        // Stage the Docker build context: app.jar, lib/*.jar, a secret-bearing prod.env, runApp (the image's
        // entrypoint), the Dockerfile itself, and (if available) local ADC for testing outside a GCP platform's
        // own workload identity.
        val dockerDir = outputDir / "docker"
        IO.delete(dockerDir)
        IO.createDirectory(dockerDir / "lib")
        IO.createDirectory(dockerDir / "adc")
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

        if (adcFile.isFile)
          IO.copyFile(adcFile, dockerDir / "adc" / "application_default_credentials.json")
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
}
