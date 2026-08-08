package sgrv.be.auth

import sgrv.be.core.{Method, Route}
import sgrv.be.BackendEnvironment
import zio.ZIO
import zio.http.{Header, Request, Response}

/** Resolves the opaque browser session cookie through Firestore and reports its user to the frontend. Declared
  * `auth = false` because it must respond to unauthenticated requests too, distinguishing that case from a
  * resolution failure; it performs its own session resolution rather than relying on route-discovery gating.
  */
@Route(methods = Array(Method.GET), path = "/me", auth = false)
object Me extends (Request => ZIO[BackendEnvironment, Nothing, Response]):

  override def apply(request: Request): ZIO[BackendEnvironment, Nothing, Response] =
    SessionAuth
      .resolve(request)
      .map:
        case Right(user)    => Response.json(json(user))
        case Left(response) => response
      .map(_.addHeader(Header.CacheControl.NoStore))

  private[auth] def json(user: SessionUser): String = s"""{"email":${quote(user.email)},"name":${quote(user.name)}}"""

  private def quote(value: String): String =
    value.flatMap:
      case '"'                          => "\\\""
      case '\\'                         => "\\\\"
      case character if character < ' ' => f"\\u${character.toInt}%04x"
      case character                    => character.toString
    .mkString("\"", "", "\"")
