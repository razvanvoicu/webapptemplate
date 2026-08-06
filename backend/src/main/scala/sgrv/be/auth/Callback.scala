package sgrv.be.auth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import sgrv.be.BackendEnvironment
import sgrv.be.core.{Method, Route}
import zio.{durationInt, Clock, Duration, IO, UIO, ZIO}
import zio.http.{Cookie, Path, Request, Response, Status, URL}

/** Completes the Google login: verifies the `state`, exchanges the authorization code on the backend, creates an
  * opaque browser session in Firestore, and sends its key as a cookie before returning to the home page.
  */
@Route(methods = Array(Method.GET), path = "/auth/callback")
object Callback extends (Request => ZIO[BackendEnvironment, Nothing, Response]):

  private[auth] val sessionCookieName = "session"
  private val sessionLifetime         = 7.days

  override def apply(request: Request): ZIO[BackendEnvironment, Nothing, Response] =
    val login =
      for
        _             <- checkState(request)
        code <- ZIO
          .fromOption(request.queryParam("code").filter(_.nonEmpty))
          .orElseFail(new IllegalArgumentException(
            request.queryParam("error").fold("The callback carries no authorization code")(e => s"Google reported: $e")
          ))
        callbackUri <- ZIO
          .fromOption(GoogleOAuth.redirectUri(request.rawHeader("Host"), request.rawHeader("X-Forwarded-Proto")))
          .orElseFail(new IllegalArgumentException("The request carries no Host header"))
        authentication <- GoogleOAuth.authenticate(code, callbackUri)
        now            <- Clock.instant
        sessionKey     <- TokenGenerator.generate(32)
        _ <- SessionStore.create(
          sessionKey,
          authentication.user,
          now,
          now.plus(sessionLifetime),
          authentication.refreshToken
        )
        _ <- ZIO.logInfo(s"Created browser session for ${authentication.user.email}")
        secure = callbackUri.startsWith("https")
      yield Response
        .redirect(URL.root)
        .addCookie(sessionCookie(sessionKey, secure))
        .addCookie(clearedStateCookie(secure))
    login.catchAll: error =>
      ZIO.logWarning(s"Google login failed: ${error.getMessage}") *>
        ZIO.succeed(Response.text("Login failed. Return to the home page and try again.").status(Status.Unauthorized))

  private def checkState(request: Request): IO[IllegalArgumentException, Unit] =
    val provided = request.queryParam("state").getOrElse("")
    val expected = request.cookie(Login.stateCookieName).map(_.content).getOrElse("")
    if provided.nonEmpty && expected.nonEmpty &&
      MessageDigest.isEqual(provided.getBytes(UTF_8), expected.getBytes(UTF_8))
    then ZIO.unit
    else ZIO.fail(new IllegalArgumentException("The state parameter does not match the login cookie"))

  private def sessionCookie(token: String, secure: Boolean): Cookie.Response =
    Cookie.Response(
      name = sessionCookieName,
      content = token,
      path = Some(Path.root),
      isSecure = secure,
      isHttpOnly = true,
      maxAge = Some(sessionLifetime),
      sameSite = Some(Cookie.SameSite.Lax)
    )

  private def clearedStateCookie(secure: Boolean): Cookie.Response =
    Cookie.Response(
      name = Login.stateCookieName,
      content = "",
      path = Some(Login.cookiePath),
      isSecure = secure,
      isHttpOnly = true,
      maxAge = Some(Duration.Zero)
    )
