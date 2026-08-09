package sgrv.be

import zio.{Chunk, Runtime, Task, Unsafe, ZIO}
import zio.http.{Header, MediaType, Request, Status, URL}

class MainSuite extends munit.FunSuite:

  private def run[A](effect: Task[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("binds the HTTP server to a given host and port"):
    val address = Main.serverConfig("0.0.0.0", 9000).address

    assertEquals(address.getAddress.getHostAddress, "0.0.0.0")
    assertEquals(address.getPort, 9000)

  test("selects the port from arguments, environment, or the default"):
    assertEquals(Main.port(Chunk("9000"), Some("7000")), 9000)
    assertEquals(Main.port(Chunk.empty, Some("7000")), 7000)
    assertEquals(Main.port(Chunk("invalid"), Some("7000")), 8888)
    assertEquals(Main.port(Chunk.empty, None), 8888)

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
    val index   = run(Main.asset("index.html", MediaType.text.html))
    val content = run(index.body.asString)
    val missing = run(Main.asset("missing.txt", MediaType.text.plain))

    assertEquals(index.status, Status.Ok)
    assert(content.contains("<html"))
    assertEquals(index.headers.get(Header.CacheControl), Some(Header.CacheControl.MaxAge(300)))
    assertEquals(missing.status, Status.NotFound)

  test("serves a static asset through the application routes"):
    val routes   = run(ZIO.succeed(Main.staticRoutes))
    val request  = Request.get(URL.decode("/style.css").toOption.get)
    val response = run(zio.ZIO.scoped(routes.runZIO(request)))
    val content  = run(response.body.asString)
    val index    = run(zio.ZIO.scoped(routes.runZIO(Request.get(URL.decode("/").toOption.get))))

    assertEquals(response.status, Status.Ok)
    assertEquals(response.headers.get(Header.CacheControl), Some(Header.CacheControl.MaxAge(300)))
    assert(content.nonEmpty)
    assertEquals(index.headers.get(Header.CacheControl), Some(Header.CacheControl.NoCache))
