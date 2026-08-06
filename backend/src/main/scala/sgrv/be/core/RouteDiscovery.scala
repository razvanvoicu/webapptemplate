package sgrv.be.core

import io.github.classgraph.ClassGraph
import sgrv.be.BackendEnvironment
import scala.jdk.CollectionConverters.*
import zio.{Task, ZIO}
import zio.http.{Method as ZioMethod, Request, Response, RoutePattern, Routes, handler}

private[be] final case class DiscoveredRoute(
    methods: Seq[ZioMethod],
    path: String,
    handler: Request => ZIO[BackendEnvironment, Nothing, Response]
)

private[be] object RouteDiscovery:

  def discover(classLoader: ClassLoader = getClass.getClassLoader): Task[Seq[DiscoveredRoute]] =
    ZIO.scoped:
      for
        scan <- ZIO.acquireRelease(
          ZIO.attemptBlocking:
            new ClassGraph()
              .enableAnnotationInfo()
              .acceptPackages("sgrv.be")
              .overrideClassLoaders(classLoader)
              .scan()
        )(result => ZIO.attemptBlocking(result.close()).ignore)
        classNames <- ZIO.attemptBlocking:
          scan.getClassesWithAnnotation(classOf[Route]).asScala.toSeq
          .sortBy(_.getName)
          .distinctBy(_.getName.stripSuffix("$"))
          .map(_.getName)
        discovered <- ZIO.foreach(classNames)(load(_, classLoader))
      yield discovered

  private[be] def load(className: String, classLoader: ClassLoader): Task[DiscoveredRoute] =
    ZIO.attemptBlocking(loadUnsafe(className, classLoader))

  private def loadUnsafe(className: String, classLoader: ClassLoader): DiscoveredRoute =
    val annotatedClass = Class.forName(className, false, classLoader)
    val annotation     = annotatedClass.getAnnotation(classOf[Route])
    val moduleName     = annotatedClass.getName.stripSuffix("$") + "$"
    val moduleClass    = Class.forName(moduleName, true, classLoader)
    //noinspection IllegalNull
    val module         = moduleClass.getField("MODULE$").get(null)
    val routeFunction =
      try module.asInstanceOf[Request => ZIO[BackendEnvironment, Nothing, Response]]
      catch case _: ClassCastException =>
        throw new IllegalArgumentException(
          s"@${classOf[Route].getSimpleName} object ${annotatedClass.getName} must extend the backend route handler type"
        )
    DiscoveredRoute(
      annotation.methods.toSeq.map(method => ZioMethod.fromString(method.name)),
      annotation.path,
      routeFunction
    )

  private[be] def fromDiscovered(discovered: Seq[DiscoveredRoute]): Routes[BackendEnvironment, Nothing] =
    Routes.fromIterable:
      discovered.flatMap: route =>
        route.methods.map: method =>
          RoutePattern(method, route.path) -> handler((request: Request) => route.handler(request))

  def routes: Task[Routes[BackendEnvironment, Nothing]] =
    discover().map(fromDiscovered)
