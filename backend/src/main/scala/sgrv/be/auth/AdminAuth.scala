package sgrv.be.auth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import zio.{Cause, System, ZIO}
import zio.http.{Request, Response, Status}

/** Verifies the `?pwd=` query parameter against the `ADMIN_PASSWORD` environment variable for routes declared
  * `@Route(adminPwd = true)`, an alternative to a Google session for reaching a diagnostic or admin route directly
  * by URL.
  */
private[be] object AdminAuth:
  def reject(request: Request): ZIO[Any, Nothing, Option[Response]] =
    System.env("ADMIN_PASSWORD").foldZIO(
      error =>
        ZIO.logErrorCause("Could not read ADMIN_PASSWORD", Cause.fail(error)) *>
          ZIO.succeed(Some(Response.status(Status.ServiceUnavailable))),
      configured =>
        configured.map(_.trim).filter(_.nonEmpty) match
          case None =>
            ZIO.logWarning("ADMIN_PASSWORD is not configured; adminPwd-protected routes are unavailable") *>
              ZIO.succeed(Some(Response.status(Status.ServiceUnavailable)))
          case Some(password) =>
            val provided = request.queryParam("pwd").getOrElse("")
            val granted = provided.nonEmpty && MessageDigest.isEqual(provided.getBytes(UTF_8), password.getBytes(UTF_8))
            ZIO.succeed(Option.unless(granted)(Response.status(Status.Unauthorized)))
    )
