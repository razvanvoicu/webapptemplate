package sgrv.be

import java.nio.charset.StandardCharsets.UTF_8
import zio.{Chunk, Runtime, Task, Unsafe, ZIO}
import zio.http.{Header, MediaType, Request, Status, URL}

class MainSuite extends munit.FunSuite:

  private def run[A](effect: Task[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  private def resourceText(name: String): String =
    val input = Option(getClass.getClassLoader.getResourceAsStream(name)).getOrElse(fail(s"Missing resource: $name"))
    try String(input.readAllBytes(), UTF_8)
    finally input.close()

  test("binds the HTTP server to a given host and port"):
    val config = Main.serverConfig("0.0.0.0", 9000)
    val address = config.address

    assertEquals(address.getAddress.getHostAddress, "0.0.0.0")
    assertEquals(address.getPort, 9000)
    assertEquals(config.gracefulShutdownTimeout, Config.serverShutdownTimeout)
    assertEquals(Main.gracefulShutdownTimeout, Config.processShutdownTimeout)

  test("delivers the container SIGTERM directly to Java as PID 1"):
    val dockerfile = resourceText("Dockerfile")
    val launcher = resourceText("runApp")

    assert(dockerfile.linesIterator.exists(_.trim == "STOPSIGNAL SIGTERM"))
    assert(dockerfile.linesIterator.exists(_.trim == "ENTRYPOINT [\"/app/runApp\"]"))
    assert(launcher.linesIterator.exists(_.trim.startsWith("exec java ")))

  test("keeps tracked environment baselines free of task-specific origins and injected runtime secrets"):
    val prodEnv = resourceText("prod.env").linesIterator.map(_.trim).filter(_.nonEmpty).toSeq
    val testEnv = resourceText("test.env").linesIterator.map(_.trim).filter(_.nonEmpty).toSeq
    val deploymentOnlyKeys = Seq(
      "PORT=",
      "LOCAL_BASE_URL=",
      "ARTIFACT_BASE_URL=",
      "PUBLIC_BASE_URL=",
      "GOOGLE_OAUTH_CLIENT_ID=",
      "GOOGLE_OAUTH_CLIENT_SECRET=",
      "ADMIN_PASSWORD="
    )

    deploymentOnlyKeys.foreach(prefix => assert(!prodEnv.exists(_.startsWith(prefix)), s"Found $prefix in prod.env"))
    Seq("LOCAL_BASE_URL=", "ARTIFACT_BASE_URL=", "PUBLIC_BASE_URL=").foreach(prefix =>
      assert(!testEnv.exists(_.startsWith(prefix)), s"Found $prefix in test.env")
    )

  test("selects a valid port from arguments, environment, or the default"):
    assertEquals(Main.port(Chunk("9000"), Some("7000")), Right(9000))
    assertEquals(Main.port(Chunk.empty, Some("7000")), Right(7000))
    assertEquals(Main.port(Chunk.empty, None), Right(8888))

  test("rejects an invalid port from the source that wins precedence"):
    val argumentError = Main.port(Chunk("invalid"), Some("7000")).swap.toOption.get
    val environmentError = Main.port(Chunk.empty, Some("70000")).swap.toOption.get

    assertEquals(
      argumentError.getMessage,
      "Invalid port configuration from the first command-line argument: 'invalid'. " +
        "Expected an integer from 1 to 65535."
    )
    assertEquals(
      environmentError.getMessage,
      "Invalid port configuration from PORT: '70000'. Expected an integer from 1 to 65535."
    )

  test("accepts only ports in the TCP range"):
    assert(Main.port(Chunk("1"), None).isRight)
    assert(Main.port(Chunk("65535"), None).isRight)
    assert(Main.port(Chunk("0"), None).isLeft)
    assert(Main.port(Chunk("-1"), None).isLeft)
    assert(Main.port(Chunk("65536"), None).isLeft)

  test("selects the bind address from the environment, defaulting to IPv4 loopback"):
    assertEquals(Main.bindAddress(Some("0.0.0.0")), "0.0.0.0")
    assertEquals(Main.bindAddress(Some("  ")), "127.0.0.1")
    assertEquals(Main.bindAddress(None), "127.0.0.1")

  test("formats request annotations as a compact route summary"):
    val annotations = Map(
      "method" -> "GET",
      "duration_ms" -> "70",
      "url" -> "/debug",
      "response_size" -> "12392",
      "status_code" -> "200",
      "request_size" -> "0"
    )

    assertEquals(LoggerConfig.requestSummary(annotations), Some(" [GET /debug -> 200 70ms]"))
    assertEquals(LoggerConfig.requestSummary(Map.empty), None)

  test("loads packaged assets and returns not found for a missing asset"):
    val index = run(Main.asset("index.html", MediaType.text.html))
    val content = run(index.body.asString)
    val missing = run(Main.asset("missing.txt", MediaType.text.plain))

    assertEquals(index.status, Status.Ok)
    assert(content.contains("<html"))
    assertEquals(index.headers.get(Header.CacheControl), Some(Header.CacheControl.MaxAge(300)))
    assertEquals(missing.status, Status.NotFound)

  test("serves a static asset through the application routes"):
    val routes = run(ZIO.succeed(Main.staticRoutes))
    val request = Request.get(URL.decode("/style.css").toOption.get)
    val response = run(zio.ZIO.scoped(routes.runZIO(request)))
    val content = run(response.body.asString)
    val index = run(zio.ZIO.scoped(routes.runZIO(Request.get(URL.decode("/").toOption.get))))

    assertEquals(response.status, Status.Ok)
    assertEquals(response.headers.get(Header.CacheControl), Some(Header.CacheControl.MaxAge(300)))
    assert(content.nonEmpty)
    assertEquals(index.headers.get(Header.CacheControl), Some(Header.CacheControl.NoCache))
