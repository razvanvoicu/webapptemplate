package sgrv.fe

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import sgrv.api.{AboutInfo, CurrentUser, SpreadsheetContentResponse, UpsertSpreadsheetRequest}
import zio.json.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.util.{Failure, Success}

object Main:

  private enum UserState:
    case Unknown
    case Unauthenticated
    case SignedIn(email: String, displayName: String)
    case AuthenticationFailed(message: String)

  private enum SheetState:
    case Idle
    case Loading
    case Loaded(rows: Seq[Seq[String]])
    case Failed(message: String)

  private enum AboutState:
    case Closed
    case Loading
    case Loaded(information: AboutInfo)
    case Failed(message: String)

  private enum LogoutState:
    case Idle
    case InProgress
    case Failed(message: String)

  import UserState.*
  import SheetState.*

  def main(args: Array[String]): Unit =
    val user = Var[UserState](Unknown)
    val sheetName = Var("")
    val sheetState = Var[SheetState](Idle)
    val aboutState = Var[AboutState](AboutState.Closed)
    val logoutState = Var[LogoutState](LogoutState.Idle)

    dom
      .fetch("/me")
      .flatMap { response =>
        if response.ok then response.text().map(parseUser)
        else if response.status == 401 then Future.successful(Unauthenticated)
        else Future.successful(AuthenticationFailed(s"The authentication check returned ${response.status}."))
      }
      .onComplete {
        case Success(state) => user.set(state)
        case Failure(error) => user.set(AuthenticationFailed(error.getMessage))
      }

    def upsertAndFetch(): Unit =
      val name = sheetName.now().trim
      if name.isEmpty then sheetState.set(Failed("Enter a spreadsheet name first."))
      else
        sheetState.set(Loading)
        postJson("/sheets/upsert", UpsertSpreadsheetRequest(name))
          .flatMap(_ => fetchContent(name))
          .onComplete {
            case Success(rows)  => sheetState.set(Loaded(rows))
            case Failure(error) => sheetState.set(Failed(error.getMessage))
          }

    def openAbout(): Unit =
      aboutState.set(AboutState.Loading)
      fetchAbout().onComplete:
        case Success(information) if aboutState.now() == AboutState.Loading =>
          aboutState.set(AboutState.Loaded(information))
        case Failure(error) if aboutState.now() == AboutState.Loading =>
          val message = Option(error.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse("The request failed.")
          aboutState.set(AboutState.Failed(message))
        case _ => ()

    def logout(): Unit =
      if logoutState.now() != LogoutState.InProgress then
        logoutState.set(LogoutState.InProgress)
        requestLogout().onComplete:
          case Success(_) =>
            aboutState.set(AboutState.Closed)
            sheetState.set(Idle)
            user.set(Unauthenticated)
            dom.window.location.assign("/")
          case Failure(error) =>
            val message = Option(error.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse("The request failed.")
            logoutState.set(LogoutState.Failed(message))

    val app =
      div(
        cls := "app",
        child <-- user.signal.map {
          case SignedIn(_, _) =>
            div(
              cls := "user-actions",
              a(
                cls := "about-link",
                href := "/about",
                onClick.preventDefault --> { _ => openAbout() },
                "About"
              ),
              button(
                cls := "logout-link",
                typ := "button",
                disabled <-- logoutState.signal.map(_ == LogoutState.InProgress),
                child.text <-- logoutState.signal.map:
                  case LogoutState.InProgress => "Logging out…"
                  case _                      => "Logout",
                onClick --> { _ => logout() }
              )
            )
          case _ => emptyNode
        },
        div(
          cls := "home",
          child <-- user.signal.map {
            case Unknown         => emptyNode
            case Unauthenticated =>
              a(
                cls := "login-button",
                href := "/auth/login",
                "Login with Google"
              ) // Before authentication has been attempted, disply a login option
            case SignedIn(_, displayName) =>
              h1(cls := "welcome", s"Hello, $displayName!") // After successfull authentication, show welcome message
            case AuthenticationFailed(message) =>
              p(cls := "error", s"Authentication failed: $message") // Error message if authentication fails
          }
        ),
        child <-- logoutState.signal.map:
          case LogoutState.Failed(message) => p(cls := "error logout-error", s"Logout failed: $message")
          case _                           => emptyNode,
        child <-- user.signal.map {
          case SignedIn(_, _) =>
            div(
              div(
                cls := "sheet-row",
                span("Input the name of a sample spreadsheet:"),
                input(
                  typ := "text",
                  placeholder := "Sample spreadsheet",
                  onInput.mapToValue --> sheetName
                ),
                button(
                  typ := "button",
                  "Create or update spreadsheet",
                  onClick --> { _ => upsertAndFetch() }
                )
              ),
              child <-- sheetState.signal.map {
                case Idle                         => emptyNode
                case Loading                      => p("Working…")
                case Failed(reason)               => p(cls := "error", reason)
                case Loaded(rows) if rows.isEmpty => p("No rows yet.")
                case Loaded(rows)                 =>
                  table(
                    cls := "sheet-table",
                    thead(tr(th("Timestamp"), th("User agent"))),
                    tbody(rows.map(row => tr(row.map(cell => td(cell)))))
                  )
              }
            )
          case _ => emptyNode
        },
        child <-- aboutState.signal.map {
          case AboutState.Closed => emptyNode
          case state             =>
            val content = state match
              case AboutState.Loading             => p(cls := "about-status", "Loading build information…")
              case AboutState.Failed(message)     => p(cls := "error about-status", message)
              case AboutState.Loaded(information) =>
                dl(
                  cls := "about-details",
                  dt("App version"),
                  dd(information.appVersion),
                  dt("Build date"),
                  dd(information.buildDate),
                  dt("Build OS"),
                  dd(information.buildOs),
                  dt("Scala version"),
                  dd(information.scalaVersion),
                  dt("Scala.js version"),
                  dd(information.scalaJsVersion)
                )
              case AboutState.Closed => emptyNode

            div(
              cls := "about-overlay",
              onClick --> { _ => aboutState.set(AboutState.Closed) },
              div(
                cls := "about-dialog",
                role := "dialog",
                onClick --> { event => event.stopPropagation() },
                div(
                  cls := "about-header",
                  h2("About"),
                  button(
                    cls := "about-close",
                    typ := "button",
                    title := "Close",
                    onClick --> { _ => aboutState.set(AboutState.Closed) },
                    "×"
                  )
                ),
                content
              )
            )
        }
      )

    renderOnDomContentLoaded(dom.document.body, app)

  private def parseUser(
      json: String
  ): UserState = // Extract the user's name from the Google account. Default to the email address if the name is not available.
    json
      .fromJson[CurrentUser]
      .fold(
        details => AuthenticationFailed(s"The backend returned invalid user JSON: $details"),
        currentUser =>
          Option(currentUser.email)
            .map(_.trim)
            .filter(_.nonEmpty)
            .map(address =>
              SignedIn(address, Option(currentUser.name).map(_.trim).filter(_.nonEmpty).getOrElse(address))
            )
            .getOrElse(AuthenticationFailed("The backend returned no email address."))
      )

  private def postJson[A: JsonEncoder](url: String, value: A): Future[Unit] =
    val requestHeaders = new dom.Headers()
    requestHeaders.set("Content-Type", "application/json")
    val init = new dom.RequestInit:
      method = dom.HttpMethod.POST
      headers = requestHeaders
      body = value.toJson
    dom.fetch(url, init).flatMap { response =>
      if response.ok then Future.successful(())
      else response.text().flatMap(text => Future.failed(new RuntimeException(s"${response.status}: $text")))
    }

  private def requestLogout(): Future[Unit] =
    val init = new dom.RequestInit:
      method = dom.HttpMethod.POST
    dom.fetch("/logout", init).flatMap { response =>
      if response.ok then Future.successful(())
      else
        response
          .text()
          .flatMap: text =>
            val details = Option(text).map(_.trim).filter(_.nonEmpty).getOrElse(s"HTTP ${response.status}")
            Future.failed(new RuntimeException(details))
    }

  private def fetchAbout(): Future[AboutInfo] =
    dom.fetch("/about").flatMap { response =>
      if response.ok then
        response
          .text()
          .flatMap: text =>
            text
              .fromJson[AboutInfo]
              .fold(
                details => Future.failed(new RuntimeException(s"The backend returned invalid About JSON: $details")),
                Future.successful
              )
      else if response.status == 401 then
        Future.failed(new RuntimeException("Your session has expired. Reload the page and sign in again."))
      else Future.failed(new RuntimeException(s"The About request returned ${response.status}."))
    }

  private def fetchContent(name: String): Future[Seq[Seq[String]]] =
    dom.fetch(s"/sheets/content?name=${js.URIUtils.encodeURIComponent(name)}").flatMap { response =>
      if response.ok then
        response
          .text()
          .flatMap(text =>
            parseRows(text).fold(
              details =>
                Future.failed(new RuntimeException(s"The backend returned invalid spreadsheet JSON: $details")),
              rows => Future.successful(rows)
            )
          )
      else response.text().flatMap(text => Future.failed(new RuntimeException(s"${response.status}: $text")))
    }

  private def parseRows(json: String): Either[String, Seq[Seq[String]]] =
    json.fromJson[SpreadsheetContentResponse].map(_.rows)
