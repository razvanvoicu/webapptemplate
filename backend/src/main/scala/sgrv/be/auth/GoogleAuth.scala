package sgrv.be.auth

import com.google.api.client.googleapis.auth.oauth2.{
  GoogleAuthorizationCodeTokenRequest,
  GoogleIdTokenVerifier,
  GoogleRefreshTokenRequest
}
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.gson.GsonFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import zio.{Task, UIO, ZIO, ZLayer}

final case class SessionUser(email: String, name: String, refreshToken: Option[String] = None)
final case class GoogleAuthentication(user: SessionUser, refreshToken: Option[String])

/** ZIO boundary around the Google OAuth client library. */
trait GoogleOAuth:
  def authorizationUrl(redirectUri: String, state: String): UIO[String]
  def authenticate(code: String, redirectUri: String): Task[GoogleAuthentication]
  def accessToken(refreshToken: String): Task[String]

private[be] object GoogleOAuth:
  private val authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
  private val localHosts             = Set("localhost", "127.0.0.1", "[::1]")
  private val baseScopes              = Seq("openid", "email", "profile")

  def authorizationUrl(redirectUri: String, state: String): ZIO[GoogleOAuth, Nothing, String] =
    ZIO.serviceWithZIO[GoogleOAuth](_.authorizationUrl(redirectUri, state))

  def authenticate(code: String, redirectUri: String): ZIO[GoogleOAuth, Throwable, GoogleAuthentication] =
    ZIO.serviceWithZIO[GoogleOAuth](_.authenticate(code, redirectUri))

  /** Exchanges a stored OAuth refresh token for a fresh, short-lived access token, used by backend routes acting
    * on Google APIs (e.g. Sheets/Drive) on behalf of a signed-in session.
    */
  def accessToken(refreshToken: String): ZIO[GoogleOAuth, Throwable, String] =
    ZIO.serviceWithZIO[GoogleOAuth](_.accessToken(refreshToken))

  val live: ZLayer[AppConfig, Throwable, GoogleOAuth] =
    ZLayer.scoped:
      for
        config <- ZIO.service[AppConfig]
        transport <- ZIO.acquireRelease(
          ZIO.attemptBlocking(GoogleNetHttpTransport.newTrustedTransport())
        )(value => ZIO.attemptBlocking(value.shutdown()).ignore)
      yield Live(config.oauth, config.googleServices, transport)

  private final case class Live(config: OAuthConfig, googleServices: Seq[String], transport: HttpTransport)
      extends GoogleOAuth:
    private val jsonFactory = GsonFactory.getDefaultInstance

    override def authorizationUrl(redirectUri: String, state: String): UIO[String] =
      ZIO.succeed(GoogleOAuth.authorizationUrl(config.clientId, redirectUri, state, googleServices))

    override def authenticate(code: String, redirectUri: String): Task[GoogleAuthentication] =
      ZIO.attemptBlocking:
        val tokenResponse = new GoogleAuthorizationCodeTokenRequest(
          transport,
          jsonFactory,
          config.clientId,
          config.clientSecret,
          code,
          redirectUri
        ).execute()
        val verifier = new GoogleIdTokenVerifier.Builder(transport, jsonFactory)
          .setAudience(java.util.List.of(config.clientId))
          .build()
        val idToken = Option(verifier.verify(tokenResponse.getIdToken))
          .getOrElse(throw new IllegalStateException("The Google ID token failed verification"))
        val payload = idToken.getPayload
        val email = Option(payload.getEmail).map(_.trim).filter(_.nonEmpty)
          .getOrElse(throw new IllegalStateException("The Google ID token carries no email address"))
        if payload.getEmailVerified != java.lang.Boolean.TRUE then
          throw new IllegalStateException(s"Google reports $email as unverified")
        val user = SessionUser(email, displayName(Option(payload.get("name")).map(_.toString), email))
        val refreshToken = Option(tokenResponse.getRefreshToken).map(_.trim).filter(_.nonEmpty)
        GoogleAuthentication(user, refreshToken)

    override def accessToken(refreshToken: String): Task[String] =
      ZIO.attemptBlocking:
        val tokenResponse =
          new GoogleRefreshTokenRequest(transport, jsonFactory, refreshToken, config.clientId, config.clientSecret)
            .execute()
        Option(tokenResponse.getAccessToken).map(_.trim).filter(_.nonEmpty)
          .getOrElse(throw new IllegalStateException("Google returned no access token for the stored refresh token"))

  private[auth] def redirectUri(host: Option[String], forwardedProto: Option[String]): Option[String] =
    host.map(_.trim).filter(_.nonEmpty).map:
      hostAndPort => s"${scheme(hostAndPort, forwardedProto)}://$hostAndPort/auth/callback"

  private def scheme(hostAndPort: String, forwardedProto: Option[String]): String =
    forwardedProto.map(_.trim.toLowerCase).filter(Set("http", "https")).getOrElse:
      if localHosts.contains(hostAndPort.takeWhile(_ != ':').toLowerCase) then "http" else "https"

  private[auth] def authorizationUrl(
      clientId: String,
      redirectUri: String,
      state: String,
      extraScopes: Seq[String] = Seq.empty
  ): String =
    Seq(
      "client_id"     -> clientId,
      "redirect_uri"  -> redirectUri,
      "response_type" -> "code",
      "scope"         -> (baseScopes ++ extraScopes).mkString(" "),
      "state"         -> state,
      // "consent" forces Google to show the full consent screen (and reissue a refresh token) on every login,
      // rather than silently reusing a prior grant that may predate scopes this app has since started requesting.
      "prompt"        -> "select_account consent",
      "access_type"   -> "offline"
    ).map((name, value) => s"$name=${URLEncoder.encode(value, UTF_8)}")
      .mkString(s"$authorizationEndpoint?", "&", "")

  private[auth] def displayName(name: Option[String], email: String): String =
    name.map(_.trim).filter(_.nonEmpty).getOrElse:
      email.takeWhile(_ != '@') match
        case ""          => email
        case mailboxName => mailboxName
