package sgrv.fe

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import sgrv.fe.lib.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.util.{Failure, Success, Try}

object Main:

  private enum DebugState:
    case Pending
    case Loaded(text: String)
    case Failed(message: String)

  private enum UserState:
    case Unknown
    case Unauthenticated
    case SignedIn(email: String, displayName: String)
    case AuthenticationFailed(message: String)

  import DebugState.*
  import UserState.*

  def main(args: Array[String]): Unit =
    val user      = Var[UserState](Unknown)
    val debug     = Var[DebugState](Pending)
    val debugOpen = Var(false)

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

    def fetchDebug(): Unit = // Asynchronously load the system signature; useful when deploying in the cloud as serverless, to understand the running configuration
      debug.set(Pending)
      dom
        .fetch("/debug")
        .flatMap(_.text())
        .onComplete {
          case Success(text) => debug.set(Loaded(text))
          case Failure(err)  => debug.set(Failed(err.getMessage))
        }

    val app =
      div(
        cls := "app",
        div(
          cls := "home",
          child <-- user.signal.map {
            case Unknown         => emptyNode
            case Unauthenticated => a(cls := "login-button", href := "/auth/login", "Login with Google") // Before authentication has been attempted, disply a login option
            case SignedIn(_, displayName) => h1(cls := "welcome", s"Hello, $displayName!")               // After successfull authentication, show welcome message
            case AuthenticationFailed(message) => p(cls := "error", s"Authentication failed: $message")  // Error message if authentication fails
          }
        ),
        button( // show backend's underlying system signature; useful when deploying in the cloud as serverless, to understand the running configuration
          cls := "bug-button", // TODO: protect this button with further authentication
          typ := "button",
          title := "System signature",
          aria.label := "Show the system signature",
          "🐞",
          onClick --> { _ =>
            fetchDebug()
            debugOpen.set(true)
          }
        ),
        child <-- debugOpen.signal.map { // Display the system signature in a popup
          case false => emptyNode
          case true =>
            div(
              cls := "overlay",
              onClick --> { _ => debugOpen.set(false) },
              div(
                cls := "popup",
                onClick.stopPropagation --> { _ => () },
                h1("System signature"),
                child <-- debug.signal.map {
                  case Pending        => p("Loading…")
                  case Loaded(text)   => pre(cls := "debug-box", text)
                  case Failed(reason) => p(cls := "error", s"Could not reach /debug: $reason")
                }
              )
            )
        }
      )

    renderOnDomContentLoaded(dom.document.body, app)

  private def parseUser(json: String): UserState = // Extract the user's name from the Google account. Default to the email address if the name is not available.
    Try(js.JSON.parse(json)).toEither.fold(
      error => AuthenticationFailed(s"The backend returned invalid JSON: ${error.getMessage}"),
      parsed =>
        val email = parsed.selectDynamic("email").asNonEmptyString
        val name  = parsed.selectDynamic("name").asNonEmptyString
        email
          .map(address => SignedIn(address, name.getOrElse(address)))
          .getOrElse(AuthenticationFailed("The backend returned no email address."))
    )
