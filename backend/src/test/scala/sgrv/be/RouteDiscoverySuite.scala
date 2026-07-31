package sgrv.be

import sgrv.be.core.{Method, Route, RouteDiscovery}
import sgrv.be.debug.Debug
import zio.*
import zio.http.{Method as ZioMethod, Path, Request, Response, URL}

@Route(methods = Array(Method.POST, Method.PUT), path = "/echo")
object EchoRoute extends (Request => UIO[Response]):
  override def apply(request: Request): UIO[Response] =
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
    val discovered = RouteDiscovery.load(EchoRoute.getClass.getName, getClass.getClassLoader)
    val routes     = RouteDiscovery.fromDiscovered(Seq(discovered))
    val request    = Request(method = ZioMethod.POST, url = URL.decode("/echo").toOption.get)
    val response   = run(ZIO.scoped(routes.runZIO(request)))
    val body       = run(response.body.asString)

    assertEquals(discovered.methods, Seq(ZioMethod.POST, ZioMethod.PUT))
    assertEquals(body, "POST:/echo")
    assert(routes.routes.exists(_.routePattern.matches(ZioMethod.PUT, Path("/echo"))))

  test("rejects an object that is not a request handler"):
    val error = intercept[IllegalArgumentException]:
      RouteDiscovery.load(InvalidRoute.getClass.getName, getClass.getClassLoader)

    assert(error.getMessage.contains("must extend (Request => UIO[Response])"))

  test("combines discovered routes with the unchanged static routes"):
    val routes = run(Main.routes)

    assert(routes.routes.exists(_.routePattern.matches(ZioMethod.GET, Path("/debug"))))
    Seq("/", "/index.html", "/style.css", "/main.js", "/main.js.map").foreach: path =>
      assert(routes.routes.exists(_.routePattern.matches(ZioMethod.GET, Path(path))), clues(path))
