package sgrv.be.auth

import com.google.api.client.googleapis.auth.oauth2.{GoogleAuthorizationCodeTokenRequest, GoogleIdTokenVerifier}
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.gson.GsonFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import zio.{Task, UIO, ZIO, ZLayer}

final case class SessionUser(email: String, name: String)
final case class GoogleAuthentication(user: SessionUser, refreshToken: Option[String])

/** ZIO boundary around the Google OAuth client library. */
trait GoogleOAuth:
  def authorizationUrl(redirectUri: String, state: String): UIO[String]
  def authenticate(code: String, redirectUri: String): Task[GoogleAuthentication]

private[be] object GoogleOAuth:
  private val authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
  private val localHosts             = Set("localhost", "127.0.0.1", "[::1]")

  def authorizationUrl(redirectUri: String, state: String): ZIO[GoogleOAuth, Nothing, String] =
    ZIO.serviceWithZIO[GoogleOAuth](_.authorizationUrl(redirectUri, state))

  def authenticate(code: String, redirectUri: String): ZIO[GoogleOAuth, Throwable, GoogleAuthentication] =
    ZIO.serviceWithZIO[GoogleOAuth](_.authenticate(code, redirectUri))

  val live: ZLayer[AppConfig, Throwable, GoogleOAuth] =
    ZLayer.scoped:
      for
        config <- ZIO.service[AppConfig]
        transport <- ZIO.acquireRelease(
          ZIO.attemptBlocking(GoogleNetHttpTransport.newTrustedTransport())
        )(value => ZIO.attemptBlocking(value.shutdown()).ignore)
      yield Live(config.oauth, transport)

  private final case class Live(config: OAuthConfig, transport: HttpTransport) extends GoogleOAuth:
    private val jsonFactory = GsonFactory.getDefaultInstance

    override def authorizationUrl(redirectUri: String, state: String): UIO[String] =
      ZIO.succeed(GoogleOAuth.authorizationUrl(config.clientId, redirectUri, state))

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

  private[auth] def redirectUri(host: Option[String], forwardedProto: Option[String]): Option[String] =
    host.map(_.trim).filter(_.nonEmpty).map:
      hostAndPort => s"${scheme(hostAndPort, forwardedProto)}://$hostAndPort/auth/callback"

  private def scheme(hostAndPort: String, forwardedProto: Option[String]): String =
    forwardedProto.map(_.trim.toLowerCase).filter(Set("http", "https")).getOrElse:
      if localHosts.contains(hostAndPort.takeWhile(_ != ':').toLowerCase) then "http" else "https"

  private[auth] def authorizationUrl(clientId: String, redirectUri: String, state: String): String =
    Seq(
      "client_id"     -> clientId,
      "redirect_uri"  -> redirectUri,
      "response_type" -> "code",
      "scope"         -> "openid email profile",
      "state"         -> state,
      "prompt"        -> "select_account",
      "access_type"   -> "offline"
    ).map((name, value) => s"$name=${URLEncoder.encode(value, UTF_8)}")
      .mkString(s"$authorizationEndpoint?", "&", "")

  private[auth] def displayName(name: Option[String], email: String): String =
    name.map(_.trim).filter(_.nonEmpty).getOrElse:
      email.takeWhile(_ != '@') match
        case ""          => email
        case mailboxName => mailboxName
