package sgrv.be.core

import io.github.classgraph.ClassGraph
import scala.jdk.CollectionConverters.*
import zio.{Task, UIO, ZIO}
import zio.http.{Method as ZioMethod, Request, Response, RoutePattern, Routes, handler}

private[be] final case class DiscoveredRoute(
    methods: Seq[ZioMethod],
    path: String,
    handler: Request => UIO[Response]
)

private[be] object RouteDiscovery:

  def discover(classLoader: ClassLoader = getClass.getClassLoader): Task[Seq[DiscoveredRoute]] =
    ZIO.attemptBlocking:
      val scan = new ClassGraph()
        .enableAnnotationInfo()
        .acceptPackages("sgrv.be")
        .overrideClassLoaders(classLoader)
        .scan()
      try
        scan.getClassesWithAnnotation(classOf[Route]).asScala.toSeq
          .sortBy(_.getName)
          .distinctBy(_.getName.stripSuffix("$"))
          .map(classInfo => load(classInfo.getName, classLoader))
      finally scan.close()

  private[be] def load(className: String, classLoader: ClassLoader): DiscoveredRoute =
    val annotatedClass = Class.forName(className, false, classLoader)
    val annotation     = annotatedClass.getAnnotation(classOf[Route])
    val moduleName     = annotatedClass.getName.stripSuffix("$") + "$"
    val moduleClass    = Class.forName(moduleName, true, classLoader)
    //noinspection IllegalNull
    val module         = moduleClass.getField("MODULE$").get(null)
    val routeFunction =
      try module.asInstanceOf[Request => UIO[Response]]
      catch case _: ClassCastException =>
        throw new IllegalArgumentException(
          s"@${classOf[Route].getSimpleName} object ${annotatedClass.getName} must extend (Request => UIO[Response])"
        )
    DiscoveredRoute(
      annotation.methods.toSeq.map(method => ZioMethod.fromString(method.name)),
      annotation.path,
      routeFunction
    )

  private[be] def fromDiscovered(discovered: Seq[DiscoveredRoute]): Routes[Any, Nothing] =
    Routes.fromIterable:
      discovered.flatMap: route =>
        route.methods.map: method =>
          RoutePattern(method, route.path) -> handler((request: Request) => route.handler(request))

  def routes: Task[Routes[Any, Nothing]] =
    discover().map(fromDiscovered)
