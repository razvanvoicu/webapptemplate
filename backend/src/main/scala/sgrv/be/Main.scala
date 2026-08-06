package sgrv.be

import sgrv.be.auth.{AppConfig, DatabaseAdmin, GoogleOAuth, SessionStore, TokenGenerator}
import sgrv.be.core.RouteDiscovery
import zio.*
import zio.http.*
import zio.logging.*

private[be] object Config:
  val defaultPort = 8888
  val bindAddress = "127.0.0.1"
  val staticCacheCtrl = Header.CacheControl.MaxAge(300)

private[be] object LoggerConfig:
  private val request = LogFormat.make:
    (builder, _, _, _, _, _, _, _, annotations) => requestSummary(annotations).foreach(builder.appendText)
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
  import RouteDiscovery.routes

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.removeDefaultLoggers >>> consoleLogger(loggerConfig)

  private[be] def asset(fName: String, mediaType: MediaType, cacheCtrl: CacheControl = staticCacheCtrl): UIO[Response] =
    val makeResponse: Option[Array[Byte]] => Response =
      case Some(bytes) => Response(headers = Headers(ContentType(mediaType), cacheCtrl), body = fromArray(bytes))
      case None => Response.status(NotFound)
    val bytes = ZIO.attemptBlockingIO(Option(getClass.getClassLoader.getResourceAsStream(s"web/$fName"))).flatMap:
      case None => ZIO.none
      case Some(input) =>
        ZIO.acquireReleaseWith(ZIO.succeed(input))(
          stream => ZIO.attemptBlockingIO(stream.close()).ignore
        )(
          stream => ZIO.attemptBlockingIO(stream.readAllBytes()).asSome
        )
    bytes.fold(_ => Response.status(InternalServerError), makeResponse)

  private val index = asset("index.html", text.html, CacheControl.NoCache)

  private[be] def staticRoutes = Routes(
    Method.GET / ""             -> handler(index),
    Method.GET / "index.html"   -> handler(index),
    Method.GET / "style.css"    -> handler(asset("style.css", text.css)),
    Method.GET / "main.js"      -> handler(asset("main.js", text.javascript)),
    Method.GET / "main.js.map"  -> handler(asset("main.js.map", application.json))
  )

  private def port(args: Chunk[String]): UIO[Int] =
    System.env("PORT").orElseSucceed(None).map(environmentPort => port(args, environmentPort))

  private[be] def port(args: Chunk[String], environmentPort: Option[String]): Int =
    args.headOption.orElse(environmentPort).flatMap(_.toIntOption).getOrElse(defaultPort)

  private[be] def serverConfig(port: Int): Server.Config = Server.Config.default.binding(bindAddress, port)

  private type Layer = BackendEnvironment & DatabaseAdmin
  private val backendLayer: ZLayer[Any, Throwable, Layer] =
    ZLayer.make[Layer](AppConfig.live, GoogleOAuth.live, SessionStore.live, TokenGenerator.live, DatabaseAdmin.live)

  def run: ZIO[ZIOAppArgs, Any, Any] =
    val unit = for
      args              <- getArgs
      p                 <- port(args)
      applicationRoutes <- routes.map(staticRoutes ++ _)
      _ <- DatabaseAdmin.ensureDatabase.catchAll: error =>
        ZIO.logWarning(s"Could not ensure the Firestore database: ${error.getMessage}")
      _ <- ZIO.logInfo(s"Serving on http://$bindAddress:$p/")
      _ <- Server
        .serve(applicationRoutes @@ HandlerAspect.requestLogging())
        .provideSome[BackendEnvironment](Server.defaultWith(_ => serverConfig(p)))
    yield ()
    unit.provideSome[ZIOAppArgs](backendLayer)
