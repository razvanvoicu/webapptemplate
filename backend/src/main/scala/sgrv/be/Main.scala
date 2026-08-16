package sgrv.be

import sgrv.be.auth.{AppConfig, DatabaseAdmin, GoogleOAuth, SessionStore, TokenGenerator}
import sgrv.be.core.{CapabilityRegistry, RouteDiscovery}
import zio.*
import zio.http.*
import zio.logging.*

private[be] object Config:
  val defaultPort = 8888
  val serverShutdownTimeout = 8.seconds
  val processShutdownTimeout = 9.seconds
  final case class InvalidPort(source: String, value: String)
      extends IllegalArgumentException(
        s"Invalid port configuration from $source: '$value'. Expected an integer from 1 to 65535."
      )
  // 127.0.0.1 suits a same-host reverse proxy or bare-VM deployment; a container needs 0.0.0.0 (see BIND_ADDRESS
  // below), since a process bound only to loopback is unreachable from outside its own network namespace.
  val defaultBindAddress = "127.0.0.1"
  val staticCacheCtrl = Header.CacheControl.MaxAge(300)

private[be] object LoggerConfig:
  private val request = LogFormat.make: (builder, _, _, _, _, _, _, _, annotations) =>
    requestSummary(annotations).foreach(builder.appendText)
  private val logFormat = LogFormat.timestamp |-| LogFormat.level |-| LogFormat.line +
    (LogFormat.space + LogFormat.cause).filter(LogFilter.causeNonEmpty) + request
  private[be] val loggerConfig = ConsoleLoggerConfig(logFormat, LogFilter.LogLevelByNameConfig(LogLevel.Trace))

  private[be] def requestSummary(annotations: Map[String, String]): Option[String] =
    for
      method <- annotations.get("method")
      url <- annotations.get("url")
      status <- annotations.get("status_code")
      duration <- annotations.get("duration_ms")
    yield s" [$method $url -> $status ${duration}ms]"

object Main extends ZIOAppDefault:
  import Config.*
  import LoggerConfig.*
  import Body.fromArray
  import zio.http.Header.{CacheControl, ContentType}
  import MediaType.{text, application}
  import Status.{NotFound, InternalServerError}

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.removeDefaultLoggers >>> consoleLogger(loggerConfig)
  override val gracefulShutdownTimeout: Duration = processShutdownTimeout

  private[be] def asset(fName: String, mediaType: MediaType, cacheCtrl: CacheControl = staticCacheCtrl): UIO[Response] =
    val makeResponse: Option[Array[Byte]] => Response =
      case Some(bytes) => Response(headers = Headers(ContentType(mediaType), cacheCtrl), body = fromArray(bytes))
      case None        => Response.status(NotFound)
    val bytes = ZIO
      .attemptBlockingIO(Option(getClass.getClassLoader.getResourceAsStream(s"web/$fName")))
      .flatMap:
        case None        => ZIO.none
        case Some(input) =>
          ZIO.acquireReleaseWith(ZIO.succeed(input))(stream => ZIO.attemptBlockingIO(stream.close()).ignore)(stream =>
            ZIO.attemptBlockingIO(stream.readAllBytes()).asSome
          )
    bytes.fold(_ => Response.status(InternalServerError), makeResponse)

  private val index = asset("index.html", text.html, CacheControl.NoCache)

  private[be] def staticRoutes = Routes(
    Method.GET / "" -> handler(index),
    Method.GET / "index.html" -> handler(index),
    Method.GET / "style.css" -> handler(asset("style.css", text.css)),
    Method.GET / "main.js" -> handler(asset("main.js", text.javascript)),
    Method.GET / "main.js.map" -> handler(asset("main.js.map", application.json))
  )

  private def port(args: Chunk[String]): IO[InvalidPort, Int] =
    System.env("PORT").orElseSucceed(None).flatMap(environmentPort => ZIO.fromEither(port(args, environmentPort)))

  private[be] def port(args: Chunk[String], environmentPort: Option[String]): Either[InvalidPort, Int] =
    args.headOption.map("the first command-line argument" -> _).orElse(environmentPort.map("PORT" -> _)) match
      case None                  => Right(defaultPort)
      case Some((source, value)) =>
        value.toIntOption.filter(port => port >= 1 && port <= 65535).toRight(InvalidPort(source, value))

  private def bindAddress: UIO[String] =
    System.env("BIND_ADDRESS").orElseSucceed(None).map(bindAddress)

  private[be] def bindAddress(environmentValue: Option[String]): String =
    environmentValue.map(_.trim).filter(_.nonEmpty).getOrElse(defaultBindAddress)

  private[be] def serverConfig(host: String, port: Int): Server.Config =
    Server.Config.default.binding(host, port).gracefulShutdownTimeout(serverShutdownTimeout)

  private type Layer = BackendEnvironment & DatabaseAdmin
  private val backendLayer: ZLayer[Any, Throwable, Layer] =
    ZLayer.make[Layer](
      AppConfig.live,
      GoogleOAuth.live,
      SessionStore.live,
      TokenGenerator.live,
      DatabaseAdmin.live,
      Client.default
    )

  // noinspection HttpUrlsUsage
  def run: ZIO[ZIOAppArgs, Any, Any] =
    val unit = for
      args <- getArgs
      p <- port(args)
      host <- bindAddress
      environment <- ZIO.environment[BackendEnvironment]
      registry = CapabilityRegistry.fromEnvironment(environment)
      reservedPatterns = staticRoutes.routes.map(_.routePattern: Any).toSet
      applicationRoutes <- RouteDiscovery.routes(registry, reservedPatterns).map(staticRoutes ++ _)
      _ <- DatabaseAdmin.ensureDatabase.catchAll: error =>
        ZIO.logWarning(s"Could not ensure the Firestore database: ${error.getMessage}")
      _ <- DatabaseAdmin.ensureSessionTtl.catchAll: error =>
        ZIO.logWarning(s"Could not ensure the Firestore session TTL policy: ${error.getMessage}")
      _ <- ZIO.logInfo(s"Serving on http://$host:$p/")
      _ <- Server
        .serve(applicationRoutes @@ HandlerAspect.requestLogging())
        .onInterrupt(
          ZIO.logInfo(
            s"Shutdown requested; active HTTP requests have up to ${serverShutdownTimeout.render} to complete"
          )
        )
        .provide(Server.defaultWith(_ => serverConfig(host, p)))
    yield ()
    unit.provideSome[ZIOAppArgs](backendLayer)
