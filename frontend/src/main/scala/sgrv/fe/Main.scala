package sgrv.fe

import com.raquo.laminar.api.L.*
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.Thenable.Implicits.*
import scala.util.{Failure, Success}

object Main:

  private enum DebugState:
    case Pending
    case Loaded(text: String)
    case Failed(message: String)

  import DebugState.*

  def main(args: Array[String]): Unit =
    val state = Var[DebugState](Pending)

    dom
      .fetch("/debug")
      .flatMap(_.text())
      .onComplete {
        case Success(text) => state.set(Loaded(text))
        case Failure(err)  => state.set(Failed(err.getMessage))
      }

    val app =
      div(
        cls := "app",
        child <-- state.signal.map {
          case Pending        => emptyNode
          case Loaded(text)   => div(h1("System signature"), pre(cls := "debug-box", text))
          case Failed(reason) => p(cls := "error", s"Could not reach /debug: $reason")
        }
      )

    renderOnDomContentLoaded(dom.document.body, app)
