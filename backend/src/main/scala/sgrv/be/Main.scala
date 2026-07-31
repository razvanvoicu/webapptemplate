package sgrv.be

import sgrv.be.core.RouteDiscovery
import zio.*
import zio.http.*
import zio.logging.*

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object Main extends ZIOAppDefault:

  private val defaultPort = 8888
  private val bindAddress = "127.0.0.1"
  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
  private val timestamp = LogFormat.make:
    (builder, _, _, _, _, _, _, _, _) =>
      builder.appendText(timestampFormatter.format(ZonedDateTime.now().plusNanos(500_000L)))
  private val request = LogFormat.make:
    (builder, _, _, _, _, _, _, _, annotations) =>
      requestSummary(annotations).foreach(builder.appendText)
  private val logFormat =
    timestamp |-| LogFormat.level |-| LogFormat.line +
      (LogFormat.space + LogFormat.cause).filter(LogFilter.causeNonEmpty) +
      request
  private val loggerConfig =
    ConsoleLoggerConfig(
      logFormat,
      LogFilter.LogLevelByNameConfig(LogLevel.Trace)
    )

  private val staticCacheControl = Header.CacheControl.MaxAge(300)

  private[be] def requestSummary(annotations: Map[String, String]): Option[String] =
    for
      method <- annotations.get("method")
      url <- annotations.get("url")
      status <- annotations.get("status_code")
      duration <- annotations.get("duration_ms")
    yield s" [$method $url -> $status ${duration}ms]"

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> consoleLogger(loggerConfig)

  private[be] def asset(
      fileName: String,
      mediaType: MediaType,
      cacheControl: Header.CacheControl = staticCacheControl
  ): UIO[Response] =
    val makeResponse: Option[Array[Byte]] => Response =
      case Some(bytes) =>
        Response(headers = Headers(Header.ContentType(mediaType), cacheControl), body = Body.fromArray(bytes))
      case None => Response.status(Status.NotFound)
    ZIO
      .attemptBlockingIO:
        Option(getClass.getClassLoader.getResourceAsStream(s"web/$fileName")).map:
          in => try in.readAllBytes() finally in.close()
      .fold (_ => Response.status(Status.InternalServerError), makeResponse)
  


  private val index = asset("index.html", MediaType.text.html, Header.CacheControl.NoCache)

  private def staticRoutes = Routes(
    Method.GET / ""             -> handler(index),
    Method.GET / "index.html"   -> handler(index),
    Method.GET / "style.css"    -> handler(asset("style.css", MediaType.text.css)),
    Method.GET / "main.js"      -> handler(asset("main.js", MediaType.text.javascript)),
    Method.GET / "main.js.map"  -> handler(asset("main.js.map", MediaType.application.json))
  )

  private[be] def routes: Task[Routes[Any, Nothing]] = RouteDiscovery.routes.map(staticRoutes ++ _)

  private def port(args: Chunk[String]): UIO[Int] =
    System.env("PORT").orElseSucceed(None).map(environmentPort => port(args, environmentPort))

  private[be] def port(args: Chunk[String], environmentPort: Option[String]): Int =
    args.headOption
      .orElse(environmentPort)
      .flatMap(_.toIntOption)
      .getOrElse(defaultPort)

  private[be] def serverConfig(port: Int): Server.Config =
    Server.Config.default.binding(bindAddress, port)

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    for
      args <- getArgs
      p <- port(args)
      applicationRoutes <- routes
      _ <- ZIO.logInfo(s"Serving on http://$bindAddress:$p/")
      _ <- Server
        .serve(applicationRoutes @@ HandlerAspect.requestLogging())
        .provide(Server.defaultWith(_ => serverConfig(p)))
    yield ()
