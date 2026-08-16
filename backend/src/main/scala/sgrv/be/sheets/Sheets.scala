package sgrv.be.sheets

import sgrv.be.BackendCapabilities
import sgrv.be.auth.{GoogleOAuth, SessionStore, SessionUser}
import sgrv.be.core.{AccessPolicy, BackendPlugin, CapabilitySet, RequestContext}
import java.time.Instant
import zio.{Cause, IO, UIO, ZIO}
import zio.http.{Client, Header, Method, Request, Response, Routes, Status, handler}

/** Shared plumbing for the `/sheets` routes. */
private[sheets] object SheetsRoutes:
  import SheetsError.*

  def requireRefreshToken(user: SessionUser): IO[SheetsError, String] =
    ZIO.fromOption(user.refreshToken).orElseFail(Unauthenticated())

  /** `Authenticated` is guaranteed by these plugins' access policy; a different context indicates a host bug. */
  def authenticatedRequest: ZIO[RequestContext, Nothing, RequestContext.Authenticated] =
    ZIO.service[RequestContext].flatMap:
      case request: RequestContext.Authenticated => ZIO.succeed(request)
      case _: RequestContext.Public => ZIO.dieMessage("Authenticated route received a public request context")

  def responseFor(error: SheetsError): Response =
    error match
      case InvalidInput(message, _) => Response.text(message).status(Status.BadRequest)
      case _: Unauthenticated =>
        Response
          .text("Google authorization is missing or expired; sign out and sign in again.")
          .status(Status.Forbidden)
      case _: GoogleUnavailable =>
        Response.text("Google services are temporarily unavailable.").status(Status.ServiceUnavailable)
      case _: UnexpectedGoogleResponse =>
        Response.text("Google returned an unexpected response.").status(Status.BadGateway)

  def handle(error: SheetsError): UIO[Response] =
    val log = error.cause match
      case Some(cause) => ZIO.logErrorCause(error.diagnostic, Cause.fail(cause))
      case None        => ZIO.logError(error.diagnostic)
    log.as(responseFor(error))

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
        accessToken   <- GoogleOAuth.accessToken(refreshToken).mapError(SheetsError.fromAccessTokenFailure)
        httpClient    <- ZIO.service[Client]
        sheetsClient   = SheetsClient.fromClient(httpClient)
        spreadsheetId <- sheetsClient.ensureSpreadsheet(accessToken, name)
        userAgent = request.header(Header.UserAgent).map(_.renderedValue).getOrElse("unknown")
        _ <- sheetsClient.appendRow(accessToken, spreadsheetId, Seq(Instant.now().toString, userAgent))
      yield Response.json(s"""{"spreadsheetId":${quote(spreadsheetId)}}""")
    result.foldZIO(handle, ZIO.succeed)

  private def requireName(request: Request): IO[SheetsError, String] =
    for
      body <- request.body.asString.mapError(error =>
        SheetsError.InvalidInput("The request body could not be read.", Some(error))
      )
      name <- ZIO
        .fromOption(extractName(body))
        .orElseFail(SheetsError.InvalidInput("Missing or empty \"name\" in the request body"))
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
          .orElseFail(SheetsError.InvalidInput("Missing \"name\" query parameter"))
        accessToken <- GoogleOAuth.accessToken(refreshToken).mapError(SheetsError.fromAccessTokenFailure)
        httpClient  <- ZIO.service[Client]
        sheetsClient = SheetsClient.fromClient(httpClient)
        spreadsheetId <- sheetsClient.findSpreadsheet(accessToken, name)
        rows <- spreadsheetId.fold(ZIO.succeed(Seq.empty[Seq[String]])):
          id => sheetsClient.readColumns(accessToken, id, "A:B")
      yield Response.json(rowsJson(rows))
    result.foldZIO(handle, ZIO.succeed)
