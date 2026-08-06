package sgrv.be.auth

import sgrv.be.core.{Method, Route}
import sgrv.be.BackendEnvironment
import zio.{Clock, ZIO}
import zio.http.{Header, Request, Response, Status}

/** Resolves the opaque browser session cookie through Firestore and reports its user to the frontend.
  */
@Route(methods = Array(Method.GET), path = "/me")
object Me extends (Request => ZIO[BackendEnvironment, Nothing, Response]):

  override def apply(request: Request): ZIO[BackendEnvironment, Nothing, Response] =
    val sessionUser = for
      now <- Clock.instant
      user <- request.cookie(Callback.sessionCookieName) match
        case Some(cookie) => SessionStore.find(cookie.content, now)
        case None         => ZIO.succeed(None)
    yield user
    sessionUser.foldZIO(
      error =>
        ZIO.logErrorCause("Could not resolve the browser session", zio.Cause.fail(error)) *>
          ZIO.succeed(Response.status(Status.ServiceUnavailable)),
      {
        case Some(user) => ZIO.succeed(Response.json(json(user)))
        case None       => ZIO.succeed(Response.status(Status.Unauthorized))
      }
    )
      .map(_.addHeader(Header.CacheControl.NoStore))

  private[auth] def json(user: SessionUser): String =
    s"""{"email":${quote(user.email)},"name":${quote(user.name)}}"""

  private def quote(value: String): String =
    value.flatMap {
      case '"'                          => "\\\""
      case '\\'                         => "\\\\"
      case character if character < ' ' => f"\\u${character.toInt}%04x"
      case character                    => character.toString
    }.mkString("\"", "", "\"")
