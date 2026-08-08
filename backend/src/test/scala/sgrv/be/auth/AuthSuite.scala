package sgrv.be.auth

import sgrv.be.core.RouteDiscovery
import zio.{Runtime, Task, Unsafe, ZIO}
import zio.http.{Path, Method as ZioMethod}

class AuthSuite extends munit.FunSuite:

  private def run[A](effect: Task[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()
    }

  test("derives the callback URI from the host and the forwarded protocol"):
    assertEquals(GoogleOAuth.redirectUri(Some("localhost:8888"), None), Some("http://localhost:8888/auth/callback"))
    assertEquals(GoogleOAuth.redirectUri(Some("127.0.0.1:8888"), None), Some("http://127.0.0.1:8888/auth/callback"))
    assertEquals(GoogleOAuth.redirectUri(Some("app.example.com"), None), Some("https://app.example.com/auth/callback"))
    assertEquals(
      GoogleOAuth.redirectUri(Some("app.example.com"), Some("http")),
      Some("http://app.example.com/auth/callback")
    )
    assertEquals(GoogleOAuth.redirectUri(None, None), None)
    assertEquals(GoogleOAuth.redirectUri(Some("  "), Some("https")), None)

  test("builds the Google authorization URL with encoded parameters"):
    val url = GoogleOAuth.authorizationUrl("client-1", "http://localhost:8888/auth/callback", "state/value")

    assert(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
    assert(url.contains("client_id=client-1"))
    assert(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8888%2Fauth%2Fcallback"))
    assert(url.contains("response_type=code"))
    assert(url.contains("scope=openid+email+profile"))
    assert(url.contains("state=state%2Fvalue"))
    assert(url.contains("access_type=offline"))

  test("appends configured Google service scopes to the authorization URL"):
    val url = GoogleOAuth.authorizationUrl(
      "client-1",
      "http://localhost:8888/auth/callback",
      "state",
      extraScopes = Seq("https://www.googleapis.com/auth/spreadsheets", "https://www.googleapis.com/auth/drive.file")
    )

    assert(url.contains(
      "scope=openid+email+profile+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fspreadsheets" +
        "+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file"
    ))

  test("loads and trims the environment-supplied OAuth configuration"):
    val configuration = AppConfig.fromEnvironment(Map(
      "GOOGLE_OAUTH_CLIENT_ID" -> " client-id ",
      "GOOGLE_OAUTH_CLIENT_SECRET" -> " client-secret ",
      "GCP_PROJECT_ID" -> " project-id ",
      "FIRESTORE_DATABASE_ID" -> " database-id ",
      "FIRESTORE_LOCATION" -> " location "
    ))

    assertEquals(
      configuration,
      Right(AppConfig(OAuthConfig("client-id", "client-secret"), FirestoreConfig("project-id", "database-id", "location")))
    )

  test("parses GOOGLE_SERVICES as a trimmed, comma-separated scope list"):
    assertEquals(AppConfig.googleServices(Map.empty), Seq.empty)
    assertEquals(AppConfig.googleServices(Map("GOOGLE_SERVICES" -> "")), Seq.empty)
    assertEquals(
      AppConfig.googleServices(Map("GOOGLE_SERVICES" -> " scope-a , scope-b ,,scope-c")),
      Seq("scope-a", "scope-b", "scope-c")
    )

  test("rejects an incomplete OAuth configuration"):
    val error = AppConfig.fromEnvironment(Map("GOOGLE_OAUTH_CLIENT_ID" -> "client-id")).left.toOption.get
    assertEquals(error.getMessage, "Environment variable GOOGLE_OAUTH_CLIENT_SECRET is not set or is empty; see prod.env")

  test("falls back to the mailbox name when Google supplies no display name"):
    assertEquals(GoogleOAuth.displayName(Some("Jane Doe"), "jane@example.com"), "Jane Doe")
    assertEquals(GoogleOAuth.displayName(Some("   "), "jane@example.com"), "jane")
    assertEquals(GoogleOAuth.displayName(None, "jane.doe@example.com"), "jane.doe")
    assertEquals(GoogleOAuth.displayName(None, "@example.com"), "@example.com")

  test("serialises the signed-in user as escaped JSON"):
    assertEquals(Me.json(SessionUser("a@b.c", "Jane \"JJ\" Doe")), """{"email":"a@b.c","name":"Jane \"JJ\" Doe"}""")

  test("exposes the auth routes through route discovery"):
    val routeContainer = run(RouteDiscovery.routes)
    Seq("/auth/login", "/auth/callback", "/me").foreach: path =>
      assert(routeContainer.routes.exists(_.routePattern.matches(ZioMethod.GET, Path(path))), clues(path))
