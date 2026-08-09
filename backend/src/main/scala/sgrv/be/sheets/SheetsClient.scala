package sgrv.be.sheets

import com.google.gson.{JsonObject, JsonParser}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*
import zio.{Task, ZIO, ZLayer}
import zio.http.{Body, Client, Header, Headers, MediaType, Method, Request, Response, URL}

/** ZIO boundary around the Google Sheets v4 and Drive v3 REST APIs, authenticated with a per-call OAuth access
  * token. Used by the `/sheets` routes to act on Google Sheets on behalf of a signed-in session.
  */
trait SheetsClient:
  def findSpreadsheet(accessToken: String, name: String): Task[Option[String]]
  def createSpreadsheet(accessToken: String, name: String): Task[String]
  def appendRow(accessToken: String, spreadsheetId: String, values: Seq[String]): Task[Unit]
  def readColumns(accessToken: String, spreadsheetId: String, range: String): Task[Seq[Seq[String]]]

  final def ensureSpreadsheet(accessToken: String, name: String): Task[String] =
    findSpreadsheet(accessToken, name).flatMap(_.fold(createSpreadsheet(accessToken, name))(ZIO.succeed))

private[be] object SheetsClient:
  def findSpreadsheet(accessToken: String, name: String): ZIO[SheetsClient, Throwable, Option[String]] =
    ZIO.serviceWithZIO[SheetsClient](_.findSpreadsheet(accessToken, name))

  def createSpreadsheet(accessToken: String, name: String): ZIO[SheetsClient, Throwable, String] =
    ZIO.serviceWithZIO[SheetsClient](_.createSpreadsheet(accessToken, name))

  def ensureSpreadsheet(accessToken: String, name: String): ZIO[SheetsClient, Throwable, String] =
    ZIO.serviceWithZIO[SheetsClient](_.ensureSpreadsheet(accessToken, name))

  def appendRow(accessToken: String, spreadsheetId: String, values: Seq[String]): ZIO[SheetsClient, Throwable, Unit] =
    ZIO.serviceWithZIO[SheetsClient](_.appendRow(accessToken, spreadsheetId, values))

  def readColumns(
      accessToken: String,
      spreadsheetId: String,
      range: String
  ): ZIO[SheetsClient, Throwable, Seq[Seq[String]]] =
    ZIO.serviceWithZIO[SheetsClient](_.readColumns(accessToken, spreadsheetId, range))

  val live: ZLayer[Client, Nothing, SheetsClient] = ZLayer.fromFunction(Live.apply)

  private final case class Live(client: Client) extends SheetsClient:
    private val driveFilesUrl  = "https://www.googleapis.com/drive/v3/files"
    private val sheetsBaseUrl  = "https://sheets.googleapis.com/v4/spreadsheets"

    override def findSpreadsheet(accessToken: String, name: String): Task[Option[String]] =
      for
        url  <- urlWithQuery(driveFilesUrl, Seq("q" -> driveQuery(name), "fields" -> "files(id)", "pageSize" -> "1"))
        json <- call(Method.GET, url, accessToken, body = None)
        files = Option(json.getAsJsonArray("files")).toSeq.flatMap(_.asScala)
      yield files.headOption.map(_.getAsJsonObject.get("id").getAsString)

    override def createSpreadsheet(accessToken: String, name: String): Task[String] =
      for
        url <- parseUrl(sheetsBaseUrl)
        properties = new JsonObject
        _ = properties.addProperty("title", name)
        body = new JsonObject
        _    = body.add("properties", properties)
        json <- call(Method.POST, url, accessToken, body = Some(body))
        id <- ZIO
          .attempt(json.get("spreadsheetId").getAsString)
          .orElseFail(new IllegalStateException("Google reported no spreadsheetId for the created spreadsheet"))
      yield id

    override def appendRow(accessToken: String, spreadsheetId: String, values: Seq[String]): Task[Unit] =
      for
        url <- urlWithQuery(
          s"$sheetsBaseUrl/$spreadsheetId/values/A:B:append",
          Seq("valueInputOption" -> "RAW", "insertDataOption" -> "INSERT_ROWS")
        )
        row  = new com.google.gson.JsonArray
        _    = values.foreach(row.add)
        rows = new com.google.gson.JsonArray
        _    = rows.add(row)
        body = new JsonObject
        _    = body.add("values", rows)
        _    <- call(Method.POST, url, accessToken, body = Some(body))
      yield ()

    override def readColumns(accessToken: String, spreadsheetId: String, range: String): Task[Seq[Seq[String]]] =
      for
        url   <- parseUrl(s"$sheetsBaseUrl/$spreadsheetId/values/$range")
        json  <- call(Method.GET, url, accessToken, body = None)
        values = Option(json.getAsJsonArray("values")).toSeq.flatMap(_.asScala)
      yield values.map(_.getAsJsonArray.asScala.map(_.getAsString).toSeq)

    private def driveQuery(name: String): String =
      val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
      s"mimeType='application/vnd.google-apps.spreadsheet' and trashed=false and name='$escaped'"

    private def parseUrl(raw: String): Task[URL] =
      URL.decode(raw) match
        case Right(url)   => ZIO.succeed(url)
        case Left(error)  => ZIO.fail(new IllegalArgumentException(s"Invalid Google API URL $raw: $error"))

    private def urlWithQuery(base: String, params: Seq[(String, String)]): Task[URL] =
      val query = params.map((key, value) => s"$key=${URLEncoder.encode(value, UTF_8)}").mkString("&")
      parseUrl(s"$base?$query")

    private def call(method: Method, url: URL, accessToken: String, body: Option[JsonObject]): Task[JsonObject] =
      for
        response <- client.batched(
          Request(
            method  = method,
            url     = url,
            headers = requestHeaders(accessToken, body.isDefined),
            body    = body.fold(Body.empty)(json => Body.fromString(json.toString))
          )
        )
        text <- response.body.asString
        _    <- failIfUnsuccessful(method, url, response, text)
        json <- ZIO
          .attempt(JsonParser.parseString(if text.isBlank then "{}" else text).getAsJsonObject)
          .orElseFail(new IllegalStateException(s"Google API returned a non-JSON-object response from $url: $text"))
      yield json

    private def requestHeaders(accessToken: String, hasBody: Boolean): Headers =
      val authorization = Headers(Header.Authorization.Bearer(accessToken))
      if hasBody then authorization ++ Headers(Header.ContentType(MediaType.application.json)) else authorization

    private def failIfUnsuccessful(method: Method, url: URL, response: Response, text: String): Task[Unit] =
      if response.status.isSuccess then ZIO.unit
      else ZIO.fail(new RuntimeException(s"Google API call $method $url failed with ${response.status}: $text"))
