package sgrv.be.auth

import sgrv.be.core.{Method, Route}
import sgrv.be.BackendEnvironment
import zio.{durationInt, ZIO}
import zio.http.{Cookie, Path, Request, Response, Status, URL}

/** Starts the Google OAuth 2.0 authorization-code flow with a CSRF-protecting `state` cookie. */
@Route(methods = Array(Method.GET), path = "/auth/login")
object Login extends (Request => ZIO[BackendEnvironment, Nothing, Response]):

  private[auth] val stateCookieName = "auth_state"
  private[auth] val cookiePath      = Path.root / "auth"

  override def apply(request: Request): ZIO[BackendEnvironment, Nothing, Response] =
    val redirect =
      for
        callbackUri <- ZIO
          .fromOption(GoogleOAuth.redirectUri(request.rawHeader("Host"), request.rawHeader("X-Forwarded-Proto")))
          .orElseFail(new IllegalArgumentException("The request carries no Host header"))
        state  <- TokenGenerator.generate(32)
        target <- GoogleOAuth.authorizationUrl(callbackUri, state)
        url    <- ZIO.fromEither(URL.decode(target))
      yield Response
        .redirect(url)
        .addCookie(stateCookie(state, secure = callbackUri.startsWith("https")))
    redirect.catchAll: error =>
      ZIO.logWarning(s"Google login could not start: ${error.getMessage}") *>
        ZIO.succeed(Response.text("Login is currently unavailable.").status(Status.ServiceUnavailable))

  private def stateCookie(state: String, secure: Boolean): Cookie.Response =
    Cookie.Response(
      name = stateCookieName,
      content = state,
      path = Some(cookiePath),
      isSecure = secure,
      isHttpOnly = true,
      maxAge = Some(10.minutes),
      sameSite = Some(Cookie.SameSite.Lax)
    )
