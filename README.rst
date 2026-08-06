webapptemplate
==============

A small full-stack web application template written in Scala. The frontend is
compiled with Scala.js and served by a ZIO HTTP backend from the same deployment
artifact.

Technology
----------

* Scala 3.8.4
* Scala.js and Laminar for the browser application
* ZIO HTTP for the backend server
* ZIO Logging for console logging
* Google OAuth 2.0 (google-api-client) for "Login with Google"
* Google Cloud Firestore for browser-session records
* ClassGraph for discovering independently loadable route modules
* MUnit and sbt-scoverage for backend tests and coverage

How it works
------------

The home page offers a "Login with Google" link. After a successful Google
login the page instead greets the user with ``Hello, <Name>!``. If Google
returns no non-empty name, the verified email address is displayed instead.
A bug button (🐞) in the bottom-right corner opens a pop-up that fetches
``GET /debug`` and
displays the returned system signature; a click outside the pop-up closes it.
The signature reports operating-system, disk, memory, environment, Java runtime,
and nginx reverse-proxy information when that information is available.

The backend owns the static-file routes, but application API routes are not
coupled to ``Main``. ``RouteDiscovery`` scans the ``sgrv.be`` package on the
runtime classpath for objects annotated with ``@Route`` and converts them into
ZIO HTTP routes. Route effects declare a ``BackendEnvironment`` containing the
OAuth, session-store, and token-generation services supplied by ``Main``. The
current ``sgrv.be.debug.Debug`` object supplies ``/debug``.

The Scala.js linker runs as a backend resource generator. Its ``main.js`` and
source map are copied into the backend's managed ``web`` resources beside the
hand-written ``index.html`` and ``style.css`` files. Consequently, one backend
build contains and serves the complete application.

Running locally
---------------

The development build currently uses JDK 21, Scala 3.8.4, and sbt 1.12.14.
Before running the application, configure the OAuth JSON path and Application
Default Credentials as described below. From the repository root, run:

.. code-block:: console

   sbt run

Then open http://localhost:8888/.

The server binds to IPv4 loopback (``127.0.0.1``). Its port defaults to ``8888``.
A valid first command-line argument takes precedence over the ``PORT``
environment variable:

.. code-block:: console

   sbt "run 9000"

On Windows, the development server can be stopped by port with:

.. code-block:: powershell

   ./scripts/stopapp.ps1 -Port 8888

Routes and caching
------------------

.. list-table::
   :header-rows: 1
   :widths: 24 52 24

   * - Route
     - Content
     - Cache policy
   * - ``/``
     - ``index.html``
     - ``no-cache``
   * - ``/index.html``
     - ``index.html``
     - ``no-cache``
   * - ``/style.css``
     - Hand-written stylesheet
     - 5 minutes
   * - ``/main.js``
     - Linked Scala.js application
     - 5 minutes
   * - ``/main.js.map``
     - Scala.js source map
     - 5 minutes
   * - ``/debug``
     - Backend system signature as plain text
     - ``no-store``
   * - ``/auth/login``
     - Redirect to the Google login page
     - Default
   * - ``/auth/callback``
     - Completes the Google login, then redirects to ``/``
     - Default
   * - ``/me``
     - Signed-in user as JSON, or ``401``
     - ``no-store``

Login with Google
-----------------

OAuth configuration file
~~~~~~~~~~~~~~~~~~~~~~~~

Set ``oauthConfigFile`` near the top of ``build.sbt`` to the absolute path of
the Google OAuth ``Web application`` JSON downloaded from Google Cloud. The
default template setting is:

.. code-block:: scala

   val oauthConfigFile = file("C:/Users/razva/OneDrive/code/@secrets/webapptemplate/oauth.config.json")

Keep this file outside the repository. It must use Google's standard structure
and contain ``web.client_id`` and ``web.client_secret``. For ``sbt run``, the
build reads these fields and supplies them to the backend as environment
variables. For ``sbt artifact``, it appends them only to the ``prod.env`` copy
inside the staged deployment ZIP; the source ``prod.env`` remains secret-free.
The ZIP therefore contains a client secret and must be stored and distributed
as a secret-bearing deployment artifact.

The backend reads the OAuth client ID and secret directly from its environment;
it does not copy them into Firestore. The external JSON is the local source of
truth, while the generated ``prod.env`` copy is the source of truth inside a
deployment ZIP.

Authentication uses the server-side OAuth 2.0 authorization-code flow.
``/auth/login`` redirects the browser to Google with a CSRF-protecting
``state`` cookie. Google redirects back to ``/auth/callback``, where the
backend exchanges the authorization code, verifies the Google-signed ID token
(signature, audience, issuer, expiry, and verified email), creates a new
browser-session document in Firestore, and sets an HttpOnly cookie containing
only that session's random key before redirecting to the home page. The
frontend calls ``GET /me`` to resolve the session. The frontend represents the
result explicitly as signed in, unauthenticated (``401``), or authentication
failed (malformed responses, unexpected status codes, and network failures).
OAuth tokens, the client secret, and Firestore writes never reach frontend
JavaScript. The HttpOnly attribute also prevents JavaScript from reading the
session key, although same-origin JavaScript can still issue requests carrying
the cookie.

The Google and Firestore SDKs are isolated behind ZIO service interfaces.
``AppConfig`` loads and validates deployment settings as an effect;
``GoogleOAuth``, ``SessionStore``, ``DatabaseAdmin``, and ``TokenGenerator``
are supplied through layers. External clients are scoped resources and are
closed automatically when the application stops. Google ``ApiFuture`` values
are bridged asynchronously into interruptible ZIO effects instead of blocking
a worker thread with ``Future.get``.

The backend reaches Google Cloud through Application Default Credentials, so
no code changes are needed between environments:

* Locally, log in manually (outside the build) with
  ``gcloud auth application-default login
  --impersonate-service-account=<GCP_PROJECT_ID's Firestore service account>``.
* On Cloud Run, deploy the service with that same service account.

The GCP project, the Firestore database ID, and its location live in
``prod.env`` (``GCP_PROJECT_ID``, ``FIRESTORE_DATABASE_ID``,
``FIRESTORE_LOCATION``) rather than in source, so a fork of this template
only needs to edit that one file. ``sbt run`` sources ``prod.env`` into the
forked process automatically, and the packaged ``runApp``/``runApp.bat``
launchers do the same in a deployment, so nothing needs to be set by hand in
either place. On startup the backend checks for the Firestore database named
``FIRESTORE_DATABASE_ID`` and creates it in Native mode at
``FIRESTORE_LOCATION`` if it does not exist. A failed initialization is logged
as a warning so the HTTP server can still start, but database-backed login and
session checks cannot succeed until Firestore is available.

One-time setup:

1. In the Google Cloud console of the target project, create an OAuth 2.0
   web client and register every callback URI the app will use, for example
   ``http://localhost:8888/auth/callback`` and
   ``https://<service>.run.app/auth/callback``. The callback URI is derived
   from the request's ``Host`` and ``X-Forwarded-Proto`` headers at runtime.
2. Set ``oauthConfigFile`` in ``build.sbt`` to the downloaded OAuth client JSON.
   The build injects its values into the local process or deployment artifact.

Each document in the ``Access`` collection represents one browser session. Its
document ID is a random 256-bit URL-safe session key, which is also stored in
the ``sessionKey`` field. The other fields are ``email``, ``name``,
``createdAt``, and ``expiresAt``. If Google returns an OAuth refresh token, it
is stored as ``refreshToken``; otherwise that field is omitted. A protected
request is authenticated by looking up the cookie's session key and rejecting
missing or expired records. Deleting one document revokes only that browser
session. There is no process-global encryption key and backend restarts do not
invalidate sessions; Firestore is the durable session store. Treat document
IDs, ``sessionKey``, and ``refreshToken`` values as secrets.

A missing or expired session produces ``401 Unauthorized``. A Firestore error
produces ``503 Service Unavailable`` and is logged, rather than being presented
to the frontend as an unauthenticated session.

The session cookie is HttpOnly, ``SameSite=Lax``, scoped to ``/``, and expires
after seven days. It is marked Secure when the callback is served over HTTPS.
The OAuth ``state`` cookie has the same browser protections and expires after
ten minutes.

Adding a route module
---------------------

Place each route in its own package below ``sgrv.be``. Its top-level object must
be annotated with ``sgrv.be.core.Route`` and implement
``Request => ZIO[BackendEnvironment, Nothing, Response]``. For example:

.. code-block:: scala

   package sgrv.be.example

   import sgrv.be.BackendEnvironment
   import sgrv.be.core.{Method, Route}
   import zio.ZIO
   import zio.http.{Request, Response}

   @Route(methods = Array(Method.GET), path = "/example")
   object Example extends (Request => ZIO[BackendEnvironment, Nothing, Response]):
     override def apply(request: Request): ZIO[BackendEnvironment, Nothing, Response] =
       ZIO.succeed(Response.text("example"))

``Method`` is a Java enum because runtime annotation arguments must be JVM
annotation constants. Route discovery translates its values into ZIO HTTP
methods.

Logging
-------

Every HTTP request is logged by a ZIO HTTP handler aspect. Console output uses a
compact format with a millisecond timestamp and request summary:

.. code-block:: text

   2026-08-01T03:29:51.927 INFO Http request served [GET /debug -> 200 70ms]

The logger accepts ``TRACE`` and higher levels. It deliberately omits fiber IDs
and request and response sizes from the text output.

Tests and coverage
------------------

Run all tests with:

.. code-block:: console

   sbt test

The backend tests cover server configuration and static assets, route discovery,
request-log formatting, debug signature generation, OAuth configuration and
URL generation, user-name fallback, authentication JSON, and discovery of the
authentication routes. They use deterministic test data; they do not call
Google or a live Firestore database. Generate an scoverage report for the
backend with:

.. code-block:: console

   sbt clean "project backend" coverage test coverageReport

Coverage instrumentation is disabled for the root project and Scala.js frontend.
The generated HTML report is
``backend/target/scala-3.8.4/scoverage-report/index.html``.

Packaging and deployment
------------------------

Build a deployment ZIP with:

.. code-block:: console

   sbt artifact

This performs a clean build and creates
``backend/target/webapptemplate-<version>.zip``. The archive contains the
application JAR, all runtime dependency JARs, a generated ``prod.env`` containing
the OAuth client configuration, and launchers for Unix-like systems and Windows.
The project currently produces this ZIP; Docker-image packaging is not yet part
of the build.

After extracting the archive, start it on Linux or macOS with:

.. code-block:: console

   sh runApp

Or on Windows with:

.. code-block:: doscon

   runApp.bat

Both launchers load ``prod.env`` before starting ``sgrv.be.Main``. A compatible
Java runtime must be installed on the deployment host.

The current HTTP server binds specifically to ``127.0.0.1``. That is convenient
for local development and a same-host reverse proxy, but a container platform
that expects the process to listen on ``0.0.0.0`` requires a corresponding
server configuration change.

Repository layout
-----------------

.. code-block:: text

   build.sbt
   project/OAuthBuild.scala
   project/Dependencies.scala
   project/plugins.sbt
   frontend/src/main/scala/sgrv/fe/Main.scala
   frontend/src/main/scala/sgrv/fe/lib/JsDynamicOption.scala
   backend/src/main/scala/sgrv/be/BackendEnvironment.scala
   backend/src/main/scala/sgrv/be/Main.scala
   backend/src/main/scala/sgrv/be/auth/
   backend/src/main/scala/sgrv/be/core/
   backend/src/main/scala/sgrv/be/debug/
   backend/src/main/resources/prod.env
   backend/src/main/resources/web/
   backend/src/test/scala/
   scripts/stopapp.ps1

Security warning
----------------

The ``/debug`` route is currently unauthenticated and intentionally exposes
sensitive diagnostic data, including all environment-variable values and
potentially nginx configuration. Because OAuth credentials are injected into
the environment, this includes ``GOOGLE_OAUTH_CLIENT_SECRET``. Hiding the
frontend button is not sufficient: protect or remove the backend route before
making the application available to untrusted clients.
