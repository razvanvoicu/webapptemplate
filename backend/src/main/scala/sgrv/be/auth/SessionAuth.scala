package sgrv.be.auth

import sgrv.be.BackendEnvironment
import zio.{Cause, Clock, ZIO}
import zio.http.{Request, Response, Status}

/** Resolves the opaque browser session cookie into its signed-in user. Used by [[Me]] to report the session to the
  * frontend and by [[sgrv.be.core.RouteDiscovery]] to gate routes declared `@Route(auth = true)`.
  */
private[be] object SessionAuth:

  def resolve(request: Request): ZIO[BackendEnvironment, Nothing, Either[Response, SessionUser]] =
    val sessionUser = for
      now <- Clock.instant
      user <- request.cookie(Callback.sessionCookieName) match
        case Some(cookie) => SessionStore.find(cookie.content, now)
        case None         => ZIO.succeed(None)
    yield user
    sessionUser.foldZIO(
      error =>
        ZIO.logErrorCause("Could not resolve the browser session", Cause.fail(error)) *>
          ZIO.succeed(Left(Response.status(Status.ServiceUnavailable))),
      {
        case Some(user) => ZIO.succeed(Right(user))
        case None       => ZIO.succeed(Left(Response.status(Status.Unauthorized)))
      }
    )

  /** As [[resolve]], but discards the resolved user: `None` if the request may proceed, `Some(response)` with the
    * response to short-circuit with otherwise. Used by [[sgrv.be.core.RouteDiscovery]] to gate `auth = true` routes.
    */
  def reject(request: Request): ZIO[BackendEnvironment, Nothing, Option[Response]] =
    resolve(request).map(_.left.toOption)
