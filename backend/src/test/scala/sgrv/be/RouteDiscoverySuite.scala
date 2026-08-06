package sgrv.be

import sgrv.be.core.{Method, Route, RouteDiscovery}
import sgrv.be.debug.Debug
import zio.*
import zio.http.{Method as ZioMethod, Path, Request, Response, URL}

@Route(methods = Array(Method.POST, Method.PUT), path = "/echo")
object EchoRoute extends (Request => ZIO[BackendEnvironment, Nothing, Response]):
  override def apply(request: Request): ZIO[BackendEnvironment, Nothing, Response] =
    ZIO.succeed(Response.text(s"${request.method.name}:${request.url.path}"))

object InvalidRoute

class RouteDiscoverySuite extends munit.FunSuite:

  private def run[A](effect: Task[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("discovers the annotated Debug route object"):
    val discovered = run(RouteDiscovery.discover())
    val debugRoutes = discovered.filter(_.path == "/debug")

    assertEquals(debugRoutes.size, 1)
    assertEquals(debugRoutes.head.methods, Seq(ZioMethod.GET))
    assert(debugRoutes.head.handler.asInstanceOf[AnyRef] eq Debug)

  test("loads every declared method and forwards the request to the module"):
    val discovered = run(RouteDiscovery.load(EchoRoute.getClass.getName, getClass.getClassLoader))
    val routes     = RouteDiscovery.fromDiscovered(Seq(discovered))
    val request    = Request(method = ZioMethod.POST, url = URL.decode("/echo").toOption.get)
    val response   = run(ZIO.scoped(routes.runZIO(request)).asInstanceOf[Task[Response]])
    val body       = run(response.body.asString)

    assertEquals(discovered.methods, Seq(ZioMethod.POST, ZioMethod.PUT))
    assertEquals(body, "POST:/echo")
    assert(routes.routes.exists(_.routePattern.matches(ZioMethod.PUT, Path("/echo"))))

  test("rejects an object that is not a request handler"):
    val result = run(RouteDiscovery.load(InvalidRoute.getClass.getName, getClass.getClassLoader).either)
    val error = result match
      case Left(value: IllegalArgumentException) => value
      case Left(value) => fail(s"Expected IllegalArgumentException, got ${value.getClass.getName}: ${value.getMessage}")
      case Right(value) => fail(s"Expected route loading to fail, got $value")

    assert(error.getMessage.contains("must extend the backend route handler type"))

  test("combines discovered routes with the unchanged static routes"):
    val sRoutes = run(ZIO.succeed(Main.staticRoutes))
    val dRoutes = run(RouteDiscovery.routes)

    assert(dRoutes.routes.exists(_.routePattern.matches(ZioMethod.GET, Path("/debug"))))
    Seq("/", "/index.html", "/style.css", "/main.js", "/main.js.map").foreach: path =>
      assert(sRoutes.routes.exists(_.routePattern.matches(ZioMethod.GET, Path(path))), clues(path))
