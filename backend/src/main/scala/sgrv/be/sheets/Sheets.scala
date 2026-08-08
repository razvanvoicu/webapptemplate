package sgrv.be.sheets

import sgrv.be.BackendEnvironment
import sgrv.be.auth.{GoogleOAuth, SessionAuth}
import sgrv.be.core.{Method, Route}
import java.time.Instant
import zio.{IO, ZIO}
import zio.http.{Header, Request, Response, Status}

/** Shared plumbing for the `/sheets` routes. */
private[sheets] object SheetsRoutes:
  /** Both `/sheets` routes need the signed-in session's stored Google refresh token, not just the yes/no answer
    * route discovery's `auth = true` gate already checked.
    */
  def requireRefreshToken(request: Request): ZIO[BackendEnvironment, Response, String] =
    SessionAuth.resolve(request).flatMap:
      case Left(response) => ZIO.fail(response)
      case Right(user)    => ZIO.fromOption(user.refreshToken).orElseFail(noRefreshTokenResponse)

  private val noRefreshTokenResponse: Response =
    Response
      .text("This session was authorized before spreadsheet access was requested; sign out and sign in again.")
      .status(Status.Forbidden)

  def badRequest(message: String): Response = Response.text(message).status(Status.BadRequest)

  def upstreamError(error: Throwable): Response =
    Response.text(s"Google API request failed: ${error.getMessage}").status(Status.BadGateway)

  def extractName(body: String): Option[String] =
    "(?s)\"name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"".r
      .findFirstMatchIn(body)
      .map(m => unescape(m.group(1)))
      .map(_.trim)
      .filter(_.nonEmpty)

  private def unescape(value: String): String =
    value.replace("\\\"", "\"").replace("\\\\", "\\")

  def rowsJson(rows: Seq[Seq[String]]): String =
    rows.map(row => row.map(quote).mkString("[", ",", "]")).mkString("""{"rows":[""", ",", "]}")

  def quote(value: String): String =
    value.flatMap {
      case '"'                          => "\\\""
      case '\\'                         => "\\\\"
      case character if character < ' ' => f"\\u${character.toInt}%04x"
      case character                    => character.toString
    }.mkString("\"", "", "\"")

/** Finds or creates a spreadsheet by name in the signed-in user's Google Drive, then appends one row recording
  * this request's server timestamp and the requesting browser's User-Agent.
  */
@Route(methods = Array(Method.POST), path = "/sheets/upsert")
object UpsertSpreadsheet extends (Request => ZIO[BackendEnvironment, Nothing, Response]):
  import SheetsRoutes.*

  override def apply(request: Request): ZIO[BackendEnvironment, Nothing, Response] =
    val result =
      for
        refreshToken  <- requireRefreshToken(request)
        name          <- requireName(request)
        accessToken   <- GoogleOAuth.accessToken(refreshToken).mapError(upstreamError)
        spreadsheetId <- SheetsClient.ensureSpreadsheet(accessToken, name).mapError(upstreamError)
        userAgent = request.header(Header.UserAgent).map(_.renderedValue).getOrElse("unknown")
        _ <- SheetsClient.appendRow(accessToken, spreadsheetId, Seq(Instant.now().toString, userAgent))
          .mapError(upstreamError)
      yield Response.json(s"""{"spreadsheetId":${quote(spreadsheetId)}}""")
    result.merge

  private def requireName(request: Request): IO[Response, String] =
    for
      body <- request.body.asString.mapError(upstreamError)
      name <- ZIO.fromOption(extractName(body)).orElseFail(badRequest("Missing or empty \"name\" in the request body"))
    yield name

/** Reports the current content of columns A and B of a spreadsheet by name, or an empty result if no spreadsheet
  * with that name exists yet for the signed-in user.
  */
@Route(methods = Array(Method.GET), path = "/sheets/content")
object SpreadsheetContent extends (Request => ZIO[BackendEnvironment, Nothing, Response]):
  import SheetsRoutes.*

  override def apply(request: Request): ZIO[BackendEnvironment, Nothing, Response] =
    val result =
      for
        refreshToken <- requireRefreshToken(request)
        name <- ZIO
          .fromOption(request.queryParam("name").map(_.trim).filter(_.nonEmpty))
          .orElseFail(badRequest("Missing \"name\" query parameter"))
        accessToken   <- GoogleOAuth.accessToken(refreshToken).mapError(upstreamError)
        spreadsheetId <- SheetsClient.findSpreadsheet(accessToken, name).mapError(upstreamError)
        rows <- spreadsheetId.fold(ZIO.succeed(Seq.empty[Seq[String]])):
          id => SheetsClient.readColumns(accessToken, id, "A:B").mapError(upstreamError)
      yield Response.json(rowsJson(rows))
    result.merge
