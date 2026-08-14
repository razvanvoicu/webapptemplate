package sgrv.be.sheets

import sgrv.be.BackendCapabilities
import sgrv.be.auth.{GoogleOAuth, SessionStore, SessionUser}
import sgrv.be.core.{AccessPolicy, BackendPlugin, CapabilitySet, RequestContext}
import java.time.Instant
import zio.{IO, ZIO}
import zio.http.{Client, Header, Method, Request, Response, Routes, Status, handler}

/** Shared plumbing for the `/sheets` routes. */
private[sheets] object SheetsRoutes:
  def requireRefreshToken(user: SessionUser): IO[Response, String] =
    ZIO.fromOption(user.refreshToken).orElseFail(noRefreshTokenResponse)

  /** `Authenticated` is guaranteed by these plugins' access policy; a different context indicates a host bug. */
  def authenticatedRequest: ZIO[RequestContext, Nothing, RequestContext.Authenticated] =
    ZIO.service[RequestContext].flatMap:
      case request: RequestContext.Authenticated => ZIO.succeed(request)
      case _: RequestContext.Public => ZIO.dieMessage("Authenticated route received a public request context")

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
object UpsertSpreadsheet extends BackendPlugin:
  type Requires = GoogleOAuth & SessionStore & Client

  override val id = "sheets-upsert"
  override val requirements: CapabilitySet[Requires] =
    CapabilitySet.one(BackendCapabilities.googleOAuth) ++
      CapabilitySet.one(BackendCapabilities.sessionStore) ++
      CapabilitySet.one(BackendCapabilities.httpClient)
  override val accessPolicy: AccessPolicy[Requires] = AccessPolicy.Authenticated
  override val routes: Routes[Requires & RequestContext, Nothing] =
    Routes(Method.POST / "sheets" / "upsert" -> handler(SheetsRoutes.authenticatedRequest.flatMap(apply)))

  import SheetsRoutes.*

  private def apply(authenticated: RequestContext.Authenticated): ZIO[Requires, Nothing, Response] =
    val request = authenticated.request
    val result =
      for
        refreshToken  <- requireRefreshToken(authenticated.user)
        name          <- requireName(request)
        accessToken   <- GoogleOAuth.accessToken(refreshToken).mapError(upstreamError)
        httpClient    <- ZIO.service[Client]
        sheetsClient   = SheetsClient.fromClient(httpClient)
        spreadsheetId <- sheetsClient.ensureSpreadsheet(accessToken, name).mapError(upstreamError)
        userAgent = request.header(Header.UserAgent).map(_.renderedValue).getOrElse("unknown")
        _ <- sheetsClient.appendRow(accessToken, spreadsheetId, Seq(Instant.now().toString, userAgent))
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
object SpreadsheetContent extends BackendPlugin:
  type Requires = GoogleOAuth & SessionStore & Client

  override val id = "sheets-content"
  override val requirements: CapabilitySet[Requires] =
    CapabilitySet.one(BackendCapabilities.googleOAuth) ++
      CapabilitySet.one(BackendCapabilities.sessionStore) ++
      CapabilitySet.one(BackendCapabilities.httpClient)
  override val accessPolicy: AccessPolicy[Requires] = AccessPolicy.Authenticated
  override val routes: Routes[Requires & RequestContext, Nothing] =
    Routes(Method.GET / "sheets" / "content" -> handler(SheetsRoutes.authenticatedRequest.flatMap(apply)))

  import SheetsRoutes.*

  private def apply(authenticated: RequestContext.Authenticated): ZIO[Requires, Nothing, Response] =
    val request = authenticated.request
    val result =
      for
        refreshToken <- requireRefreshToken(authenticated.user)
        name <- ZIO
          .fromOption(request.queryParam("name").map(_.trim).filter(_.nonEmpty))
          .orElseFail(badRequest("Missing \"name\" query parameter"))
        accessToken <- GoogleOAuth.accessToken(refreshToken).mapError(upstreamError)
        httpClient  <- ZIO.service[Client]
        sheetsClient = SheetsClient.fromClient(httpClient)
        spreadsheetId <- sheetsClient.findSpreadsheet(accessToken, name).mapError(upstreamError)
        rows <- spreadsheetId.fold(ZIO.succeed(Seq.empty[Seq[String]])):
          id => sheetsClient.readColumns(accessToken, id, "A:B").mapError(upstreamError)
      yield Response.json(rowsJson(rows))
    result.merge
