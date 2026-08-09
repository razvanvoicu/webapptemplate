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
* Google OAuth 2.0 (google-api-client) for "Login with Google", optionally with additional Google API scopes
* Google Sheets v4 / Drive v3 REST APIs (via zio-http's ``Client`` and Gson) as a worked example of using those
  entitlements
* Google Cloud Firestore for browser-session records
* ClassGraph for discovering independently loadable route modules
* MUnit and sbt-scoverage for backend tests and coverage

How it works
------------

The home page offers a "Login with Google" link. After a successful Google
login the page instead greets the user with ``Hello, <Name>!``. If Google
returns no non-empty name, the verified email address is displayed instead.

Once signed in, a small form appears under the greeting exercising the Google Sheets integration described in
`Google service entitlements (Sheets)`_ below: enter a spreadsheet name and click "Create or update spreadsheet"
to find-or-create that spreadsheet in the signed-in user's Google Drive, append a row recording the request's
server timestamp and the browser's User-Agent, and display the spreadsheet's current content in a table.

There is no on-page control for the diagnostic ``/debug`` route; see
`Admin-protected routes`_ below for how to reach it directly. It returns the
backend's system signature as plain text, reporting operating-system, disk,
memory, environment, Java runtime, and nginx reverse-proxy information when
that information is available.

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

``sbt run`` sets ``-Djava.net.preferIPv4Stack=true`` on the forked JVM (``run / javaOptions`` in ``build.sbt``),
and the Docker image's ``runApp`` launcher passes the same flag directly. Some networks hand out an
IPv6 (AAAA) address for ``googleapis.com`` without actually routing IPv6, which otherwise surfaces as
``io.netty.channel.AbstractChannel$AnnotatedNoRouteToHostException`` /
``java.net.NoRouteToHostException`` from outbound Google API calls (the OAuth token exchange, or the Sheets/Drive
calls in `Google service entitlements (Sheets)`_); forcing IPv4 avoids that entirely.

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
     - Backend system signature as plain text; requires sign-in and ``?pwd=``
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
   * - ``POST /sheets/upsert``
     - Finds/creates a named spreadsheet, appends a timestamp + User-Agent row; JSON ``{"spreadsheetId": ...}``
     - Default
   * - ``GET /sheets/content``
     - Columns A:B of a named spreadsheet as JSON ``{"rows": [...]}``, or an empty list if it doesn't exist
     - Default

Every discovered route requires a valid browser session by default; see ``auth`` under `Adding a route module`_
below. ``/auth/login``, ``/auth/callback``, and ``/me`` are declared ``auth = false`` because they must stay
reachable without a session (see `Login with Google`_ for why each of the three needs that). ``/debug`` keeps the
default ``auth = true`` and additionally declares ``adminPwd = true``, so reaching it needs both a session and the
admin password (`Admin-protected routes`_). ``/sheets/upsert`` and ``/sheets/content`` also keep the default
``auth = true`` with no ``adminPwd``, and additionally resolve the session themselves to reach the signed-in
user's stored Google refresh token (`Google service entitlements (Sheets)`_). Static routes (``/``,
``/index.html``, ``/style.css``, ``/main.js``, ``/main.js.map``) are wired directly in ``Main`` rather than
discovered, so neither ``auth`` nor ``adminPwd`` applies to them.

Login with Google
-----------------

OAuth configuration file
~~~~~~~~~~~~~~~~~~~~~~~~

``oauthConfigFile`` near the top of ``build.sbt`` points at the Google OAuth
``Web application`` JSON downloaded from Google Cloud, resolved from a shared
``secretsDir`` whose path depends on the current OS (OneDrive mounts under a
different root on Windows versus macOS):

.. code-block:: scala

   val secretsDir = file(if (sys.props("os.name").toLowerCase.contains("mac"))
     "/Users/<mac-username>/Library/CloudStorage/OneDrive-Personal/code/@secrets/webapptemplate"
   else
     "C:/Users/<windows-username>/OneDrive/code/@secrets/webapptemplate")
   val oauthConfigFile = secretsDir / "oauth.config.json"

Keep this file outside the repository. It must use Google's standard structure
and contain ``web.client_id`` and ``web.client_secret``. For ``sbt run``, the
build reads these fields and supplies them to the backend as environment
variables. For ``sbt artifact``, it appends them only to the ``prod.env`` copy
staged into the Docker build context; the source ``prod.env`` remains
secret-free. The resulting Docker image therefore contains a client secret and
must be handled as a secret-bearing artifact (see `Packaging and deployment`_).
``adminPasswordFile``, described in `Admin-protected routes`_, resolves from
the same ``secretsDir``.

The backend reads the OAuth client ID and secret directly from its environment;
it does not copy them into Firestore. The external JSON is the local source of
truth, while the generated ``prod.env`` copy is the source of truth inside the
Docker image.

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

The GCP project, the Firestore database ID, and its location live in an env file (``GCP_PROJECT_ID``,
``FIRESTORE_DATABASE_ID``, ``FIRESTORE_LOCATION``) rather than in source, so a fork of this template only needs
to edit that one file. Which file depends on how the backend is started, so local runs and deployments can point
at different configuration (a test Firestore database and project, say, versus the real one) without editing
source:

* ``sbt run`` sources ``backend/src/main/resources/test.env`` into the forked local process automatically.
* ``sbt artifact`` instead reads ``backend/src/main/resources/prod.env``, and both it and the OAuth/admin secrets
  from `OAuth configuration file`_ / `Admin-protected routes`_ are baked into the ``prod.env`` staged into the
  Docker build context; the image's ``runApp`` launcher sources that copy at startup.

Either way nothing needs to be set by hand at run time. On startup the backend checks for the Firestore database
named ``FIRESTORE_DATABASE_ID`` and creates it in Native mode at ``FIRESTORE_LOCATION`` if it does not exist. A
failed initialization is logged as a warning so the HTTP server can still start, but database-backed login and
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

Testing against a local Firestore emulator
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

``test.env`` sets ``FIRESTORE_EMULATOR_HOST=localhost:8880``, so ``sbt run`` talks to a local Firestore emulator
instead of the real GCP project by default. The Firestore client library detects this environment variable
itself and connects to that local, unauthenticated instance instead — skipping Application Default Credentials
entirely, so the ``gcloud auth application-default login`` step above isn't needed for local runs. Remove or
comment out the line in ``test.env`` to go back to hitting the real project locally.

``firebase.json`` at the repository root configures the emulator's port (``8880``), enables its web UI
(``4000``), and sets ``singleProjectMode: false``. ``.firebaserc`` alongside it pins the project id to
``GCP_PROJECT_ID`` (``apps-416208`` by default). Start it with the Firebase CLI (``npm install -g firebase-tools``
if you don't have it):

.. code-block:: console

   firebase emulators:start --only firestore --project=<GCP_PROJECT_ID>

With it running, browse ``http://localhost:4000/firestore`` to inspect the ``Access`` collection live while
exercising the login flow. Restarting the emulator wipes its data (it's in-memory only), so a fresh restart
means signing in again before there's anything to see.

Getting the Emulator UI to actually display data here took three separate fixes, each worth knowing about since
the failure mode of each is "looks fine, shows nothing," with no error surfaced anywhere obvious:

* ``DatabaseAdmin.live`` detects ``FIRESTORE_EMULATOR_HOST`` and points its ``FirestoreAdminClient`` at the
  emulator too (plaintext channel, no credentials) instead of real GCP. Without this, ``ensureDatabase`` would
  silently check/create the database against the real project instead — using whatever Application Default
  Credentials happen to be configured — while the emulator's own copy of the database is never created, and the
  real-GCP call "succeeds" from the app's point of view, so nothing gets logged.
* ``singleProjectMode: false`` in ``firebase.json``: the Emulator UI defaults to "demo mode," which only
  recognizes a single project.
* ``.firebaserc``: without it, the UI's own browser-side code resolves its *own* project id independently of
  both the app and any ``--project`` flag, falling back to a synthetic ``demo-no-project`` — so it queries a
  project with nothing in it while the real data sits under ``GCP_PROJECT_ID``. This one is diagnosable by
  opening the browser's network tab and checking which project id the ``listCollectionIds``/data requests to
  ``localhost:8880`` actually use.

Data can be genuinely present and readable — verifiable directly against the emulator's REST API, e.g. ``curl
http://localhost:8880/v1/projects/<GCP_PROJECT_ID>/databases/webapptemplate/documents/Access`` — while the UI
shows nothing, for any of the three reasons above.

One further caveat worth checking if session storage doesn't work against the emulator: this app uses a *named*
Firestore database (``FIRESTORE_DATABASE_ID=webapptemplate``, a newer real-Firestore feature), and older
Firestore emulator versions only emulated the single default database. If session reads/writes fail against the
emulator, try setting ``test.env``'s ``FIRESTORE_DATABASE_ID`` to ``(default)`` as a first troubleshooting step.

Google service entitlements (Sheets)
-------------------------------------

Beyond ``openid email profile``, the login flow can request additional Google API scopes, letting the backend act
on Google services on the signed-in user's behalf. ``GOOGLE_SERVICES`` in ``prod.env`` is a comma-separated list
of OAuth scope URLs; it is optional, and missing or empty requests no additional entitlements. The template
default requests Sheets creation/editing and Drive access limited to files this app created:

.. code-block:: text

   GOOGLE_SERVICES=https://www.googleapis.com/auth/spreadsheets,https://www.googleapis.com/auth/drive.file

``GoogleOAuth.authorizationUrl`` appends these scopes to the ``scope`` parameter sent to Google. Google shows a
consent screen the first time a user grants a given scope set; ``access_type=offline`` (already requested for
every login) asks for a refresh token at that point. That refresh token is stored on the browser-session document
alongside ``email``/``name`` (see `Login with Google`_) and resolved onto ``SessionUser.refreshToken`` by
``SessionStore.find``, so a route handler can call ``GoogleOAuth.accessToken(refreshToken)`` to mint a fresh,
short-lived access token for a Google API call without the user re-authenticating. If a session predates
``GOOGLE_SERVICES`` being requested, or Google didn't reissue a refresh token on a later login, that field is
``None``; routes that need it reject the request with ``403`` and ask the user to sign out and back in.

As a working example, ``sgrv.be.sheets.SheetsClient`` wraps the Google Sheets v4 and Drive v3 REST APIs (called
directly over ``zio.http.Client`` with Gson for JSON, rather than the generated ``google-api-services-*``
client libraries) and two routes use it:

* ``POST /sheets/upsert`` — body ``{"name": "<spreadsheet name>"}``. Finds a non-trashed spreadsheet with that
  name in the user's Drive (searching only files this app can see, per ``drive.file``), creates one via the
  Sheets API if none exists, then appends a row to columns A and B: the server's current timestamp and the
  request's ``User-Agent`` header.
* ``GET /sheets/content?name=<spreadsheet name>`` — returns ``{"rows": [[...], ...]}``, the current content of
  columns A and B, or an empty list if no spreadsheet with that name exists yet.

Both are discovered routes with the default ``auth = true``, so an unauthenticated request never reaches the
Sheets API; the handlers additionally resolve the session themselves (like ``/me``) to reach
``SessionUser.refreshToken``. On the frontend, once signed in, a small form under the welcome message
(``sgrv.fe.Main``) lets you exercise this end to end: enter a spreadsheet name, click "Create or update
spreadsheet" to call ``/sheets/upsert`` then ``/sheets/content``, and the fetched rows render in a table.

Admin-protected routes
-----------------------

``adminPwd`` is checked independently of ``auth``, in addition to it rather than instead of it: route discovery
requires a ``?pwd=`` query parameter equal to the ``ADMIN_PASSWORD`` environment variable before the handler
runs, regardless of the route's ``auth`` setting; a missing or incorrect password produces ``401 Unauthorized``,
and a missing or unreadable ``ADMIN_PASSWORD`` produces ``503 Service Unavailable`` (fail closed rather than fall
open).

The one diagnostic route currently in the template, ``/debug``, declares ``@Route(auth = true, adminPwd = true)``:
reaching it requires *both* a signed-in Google session and the correct password. Sign in first, then visit
``https://<host>/debug?pwd=<password>`` directly in the browser's address bar; there is intentionally no on-page
link or button to it, so it is not an obvious target for a casual or unauthenticated visitor. A route that an
operator should reach without ever signing in — a health check, say — would instead declare
``auth = false, adminPwd = true``, reachable by the password alone.

``adminPasswordFile`` near the top of ``build.sbt`` (``secretsDir / "admin.pwd"``, see `OAuth configuration
file`_) points at a plain-text file containing a single line with the admin password, kept outside the repository
beside ``oauth.config.json``. For ``sbt run``, the build reads this file and supplies it to the backend as the
``ADMIN_PASSWORD`` environment variable. For ``sbt artifact``, it appends ``ADMIN_PASSWORD`` only to the
``prod.env`` staged into the Docker build context, alongside the OAuth client secret; the source ``prod.env``
remains secret-free.

The password travels as a URL query parameter, so treat it like any other bearer credential: it can end up in
browser history and proxy or server access logs. Rotate ``admin.pwd`` and redeploy if it leaks.

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

``@Route`` also takes two boolean parameters, checked independently before the handler runs — either can reject the
request without the handler writing any authentication code itself:

* ``auth``, defaulting to ``true``. When ``true``, route discovery resolves the browser session cookie: a missing
  or expired session short-circuits with ``401``, a Firestore error short-circuits with ``503``, and only a valid
  session reaches the handler. Set ``auth = false`` on a route that must stay reachable without a session, such as
  ``/auth/login`` or a route that resolves the session itself to report both signed-in and signed-out states, such
  as ``/me``.
* ``adminPwd``, defaulting to ``false``. When ``true``, route discovery requires a ``?pwd=`` query parameter
  matching the ``ADMIN_PASSWORD`` environment variable; see `Admin-protected routes`_. Combine with
  ``auth = false`` to make a route reachable by password alone, without a Google session.

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

``sbt test`` at the root also runs the frontend's Scala.js tests, which need Node.js installed; without it,
scope the run to the backend with ``sbt "project backend" test``.

The backend tests cover server configuration and static assets, route discovery and its ``auth``/``adminPwd``
gating, request-log formatting, debug signature generation, OAuth configuration and URL generation (including
``GOOGLE_SERVICES`` parsing and the resulting scope list), user-name fallback, authentication JSON, discovery of
the authentication and Sheets routes, and the Sheets routes' JSON request/response helpers. They use deterministic
test data; they do not call Google or a live Firestore/Sheets/Drive API. Generate an scoverage report for the
backend with:

.. code-block:: console

   sbt clean "project backend" coverage test coverageReport

Coverage instrumentation is disabled for the root project and Scala.js frontend.
The generated HTML report is
``backend/target/scala-3.8.4/scoverage-report/index.html``.

Packaging and deployment
------------------------

Build a Docker image with:

.. code-block:: console

   sbt artifact

This performs a clean build, stages a Docker build context under ``backend/target/docker/`` (application JAR, all
runtime dependency JARs, a generated ``prod.env`` with the OAuth client configuration and the admin password
baked in, the ``runApp`` launcher, and the ``Dockerfile`` itself), and runs ``docker build`` there (assumed
already installed). The result is tagged both ``webapptemplate:<version>`` and ``webapptemplate:latest``.

``dockerPlatform`` near the top of ``build.sbt`` (default ``linux/amd64``) sets the image's target platform
independently of the machine running the build — e.g. building on Apple Silicon for an amd64 deployment host.
Docker/BuildKit cross-builds via emulation as needed, so nothing else has to change; it's slower than a native
build (and a container started from a foreign-platform image runs under emulation too, noticeably slower to
start than a native one) but produces a correct image either way.

The image's ``ENTRYPOINT`` runs ``runApp`` (the same launcher used before this template moved to Docker-only
packaging), which loads ``prod.env`` before starting ``sgrv.be.Main``.

The HTTP server binds to ``BIND_ADDRESS`` (``127.0.0.1`` if unset) — a process bound only to loopback is
unreachable from outside a container regardless of published ports, so the ``Dockerfile`` sets
``BIND_ADDRESS=0.0.0.0`` itself. Run the image with:

.. code-block:: console

   docker run -p 8888:8888 webapptemplate:latest

or, if a reverse proxy (e.g. nginx) on the same host will terminate HTTPS and forward to it, publish only to
loopback so nothing else on the network can reach the container directly:

.. code-block:: console

   docker run -p 127.0.0.1:8888:8888 webapptemplate:latest

Application Default Credentials work differently depending on where the image runs. On Cloud Run/GKE/GCE,
nothing needs to change: the platform's own workload identity (the attached service account) supplies ADC
automatically, exactly as described in `Login with Google`_, and the Dockerfile's baked-in credentials (see
below) are simply unused. Running the image *outside* a GCP platform — a plain ``docker run`` on any other host,
including for local testing — needs credentials from somewhere, since there's no metadata server to ask;
``sbt artifact`` bakes in whatever ADC file it finds at gcloud's own well-known location
(``~/.config/gcloud/application_default_credentials.json`` on macOS/Linux, generated by
``gcloud auth application-default login``) if present, warning and proceeding without it otherwise.

This makes the Docker image itself a secret-bearing artifact, on top of the OAuth/admin secrets already baked
into its copy of ``prod.env``. Do not push it to a public registry; transfer it directly with
``docker save``/``docker load``, or push to a private registry you control. A production deployment on
Cloud Run/GKE/GCE doesn't need the baked-in credential at all and is arguably better off without it (workload
identity rotates automatically; a baked-in file doesn't); this mechanism exists for the case of testing the
exact image you're about to deploy, or running it on a host with no workload identity to rely on, such as a
plain Linux VM behind your own reverse proxy.

Repository layout
-----------------

.. code-block:: text

   build.sbt
   firebase.json
   .firebaserc
   project/OAuthBuild.scala
   project/AdminBuild.scala
   project/Dependencies.scala
   project/plugins.sbt
   frontend/src/main/scala/sgrv/fe/Main.scala
   frontend/src/main/scala/sgrv/fe/lib/JsDynamicOption.scala
   backend/src/main/scala/sgrv/be/BackendEnvironment.scala
   backend/src/main/scala/sgrv/be/Main.scala
   backend/src/main/scala/sgrv/be/auth/
   backend/src/main/scala/sgrv/be/sheets/
   backend/src/main/scala/sgrv/be/core/
   backend/src/main/scala/sgrv/be/debug/
   backend/src/main/resources/prod.env
   backend/src/main/resources/test.env
   backend/src/main/resources/Dockerfile
   backend/src/main/resources/web/
   backend/src/test/scala/
   scripts/stopapp.ps1

Forking this template
----------------------

Forking this repository to start a new project means replacing every piece of data specific to *this*
deployment — a GCP project, an OAuth client, a couple of secret files, a handful of settings — while everything
else described above (route discovery, the ``auth``/``adminPwd`` gating, the session store, the Sheets
integration's plumbing) is generic infrastructure that keeps working unchanged underneath your own routes.

What absolutely needs changing
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

1. **A Google Cloud project of your own**, with the Firestore API enabled (the backend creates the *database*
   itself on startup, per `Login with Google`_, but the project and API enablement are manual one-time steps in
   the console). If you keep the Sheets example, also enable the Google Sheets API and Google Drive API there —
   Firestore, Sheets, and Drive are each billed/enabled independently, and API calls fail with ``403`` if the
   corresponding API isn't turned on for the project even though the OAuth scope was granted.

2. **A new OAuth 2.0 web client**, created in that project, with your own callback URIs registered (step 1 under
   `Login with Google`_'s one-time setup). Download its JSON as your fork's ``oauth.config.json`` — the one in
   this repository's secrets folder is for a specific existing GCP project and will not work for a fork.

3. **``secretsDir`` in ``build.sbt``**. The Windows/macOS paths there point at this repository's original
   author's OneDrive folder; point them at wherever *you* keep secrets outside your fork's repository (OneDrive,
   another cloud-synced folder, or a plain local directory — the mechanism is just two ``File`` paths, not
   OneDrive-specific).

4. **``backend/src/main/resources/prod.env`` and ``test.env``** — the same three settings in both (see
   `Login with Google`_ for how the two files are used differently by ``sbt run`` versus ``sbt artifact``; a
   fork can safely point them at different GCP projects, e.g. a real one and a test one):

   * ``GCP_PROJECT_ID``, ``FIRESTORE_DATABASE_ID``, ``FIRESTORE_LOCATION`` — your new project and the Firestore
     database/location you want created there.
   * ``GOOGLE_SERVICES`` — the scopes your fork's own routes need (see `Google service entitlements (Sheets)`_).
     Clear it if you don't call any Google API beyond login; keep or extend it if you do.
   * ``PORT`` can stay as-is; it is only a local-run default and is overridden by the ``PORT`` a platform like
     Cloud Run injects.

5. **A fresh ``admin.pwd``** — a new random password, if you keep any ``adminPwd = true`` routes at all (see
   "What to keep, drop, or extend" below).

6. **Application Default Credentials for the new project** (`Login with Google`_): locally,
   ``gcloud auth application-default login --impersonate-service-account=<new-project's-Firestore-service-account>``;
   in deployment, run the service under that same service account.

Worth changing, but not load-bearing
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

* ``ThisBuild / organization`` / ``organizationName`` and the three ``name`` settings in ``build.sbt``
  (``webapptemplate``, ``webapptemplate-frontend``, ``webapptemplate-backend``) — cosmetic, but they name your
  build artifacts and Docker image tags (the root project's ``name`` specifically).
* The ``<title>`` in ``backend/src/main/resources/web/index.html`` — currently "Web App Template".
* The ``sgrv.be`` / ``sgrv.fe`` package names. Purely a naming choice, but if you rename them, also update
  ``RouteDiscovery.discover``'s ``.acceptPackages("sgrv.be")`` filter in
  ``backend/src/main/scala/sgrv/be/core/RouteDiscovery.scala`` to match — otherwise route discovery silently
  finds nothing under the new package.

What to keep, drop, or extend
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Treat ``backend/src/main/scala/sgrv/be/auth/`` and ``.../core/`` as infrastructure: the OAuth flow, session
store, and route discovery/gating work as-is and shouldn't need edits unless you're changing how authentication
itself works. ``sgrv.be.debug.Debug`` and the entire ``sgrv.be.sheets`` package, by contrast, are worked
*examples* — delete either (and its frontend UI, and its ``GOOGLE_SERVICES`` scopes if dropping Sheets) if your
project has no use for them, or use them as the template for your own routes.

`Adding a route module`_ above is the generic recipe for a new route; the routes already in the repository are
worked examples of the shapes a new route is likely to take:

* **A route with no session at all** — ``sgrv.be.auth.Login`` / ``Callback`` (``auth = false``). Copy this shape
  only if you're adding another pre-authentication entry point, which is uncommon.
* **The common case: an authenticated route whose handler doesn't need to know who's signed in** — the
  ``@Route(methods = Array(Method.GET), path = "/example")`` example itself, under `Adding a route module`_.
  It keeps the default ``auth = true`` and contains no authentication code whatsoever; a request only reaches the
  handler once route discovery has already confirmed a valid session. This is the right starting point for most
  new routes.
* **A route that must serve signed-in and signed-out requests differently** — ``sgrv.be.auth.Me``
  (``auth = false``, then the handler calls ``SessionAuth.resolve`` itself to tell the two cases apart, since the
  gate's generic ``401`` wouldn't distinguish "signed out" from "session lookup failed").
* **An authenticated route whose handler needs data *from* the session** — ``sgrv.be.sheets.UpsertSpreadsheet`` /
  ``SpreadsheetContent``: default ``auth = true`` for defense in depth, and the handler additionally resolves the
  session itself (via ``SheetsRoutes.requireRefreshToken``) to reach ``SessionUser.refreshToken``.
* **A route reachable by password instead of, or in addition to, a session** — ``sgrv.be.debug.Debug``
  (``adminPwd = true``, combined with ``auth = true`` currently; see `Admin-protected routes`_ for both
  combinations).
* **Calling a *different* Google API on the user's behalf** — ``sgrv.be.sheets.SheetsClient`` is the model:
  a ZIO service trait, a companion of ``ZIO.serviceWithZIO`` accessors, and a ``Live`` case class that
  authenticates a ``zio.http.Client`` call with a Bearer access token from ``GoogleOAuth.accessToken``. To wrap a
  new Google API (Calendar, Gmail, Docs, ...): add its scope(s) to ``GOOGLE_SERVICES``, copy ``SheetsClient``'s
  shape for that API's REST calls, wire its ``live`` layer into ``BackendEnvironment`` and the ``ZLayer.make``
  call in ``Main.scala`` next to ``SheetsClient.live``, and add routes modeled on
  ``backend/src/main/scala/sgrv/be/sheets/Sheets.scala``.

Security warning
----------------

The ``/debug`` route intentionally exposes sensitive diagnostic data,
including all environment-variable values and potentially nginx
configuration. Because OAuth credentials and the admin password are injected
into the environment, this includes ``GOOGLE_OAUTH_CLIENT_SECRET`` and
``ADMIN_PASSWORD`` itself. It is gated by both a signed-in session and the
``adminPwd`` mechanism described in `Admin-protected routes`_ rather than left
open, but that second gate is only as strong as ``admin.pwd``: keep it a
long, random, secret value, and remember the caveats there about the
password appearing in a URL.
