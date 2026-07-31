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
* ClassGraph for discovering independently loadable route modules
* MUnit and sbt-scoverage for backend tests and coverage

How it works
------------

The frontend fetches ``GET /debug`` and displays the returned system signature.
The signature reports operating-system, disk, memory, environment, Java runtime,
and nginx reverse-proxy information when that information is available.

The backend owns the static-file routes, but application API routes are not
coupled to ``Main``. ``RouteDiscovery`` scans the ``sgrv.be`` package on the
runtime classpath for objects annotated with ``@Route`` and converts them into
ZIO HTTP routes. The current ``sgrv.be.debug.Debug`` object supplies ``/debug``.

The Scala.js linker runs as a backend resource generator. Its ``main.js`` and
source map are copied into the backend's managed ``web`` resources beside the
hand-written ``index.html`` and ``style.css`` files. Consequently, one backend
build contains and serves the complete application.

Running locally
---------------

The development build requires a compatible JDK and sbt. From the repository
root, run:

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

Adding a route module
---------------------

Place each route in its own package below ``sgrv.be``. Its top-level object must
be annotated with ``sgrv.be.core.Route`` and implement
``Request => UIO[Response]``. For example:

.. code-block:: scala

   package sgrv.be.example

   import sgrv.be.core.{Method, Route}
   import zio.UIO
   import zio.http.{Request, Response}

   @Route(methods = Array(Method.GET), path = "/example")
   object Example extends (Request => UIO[Response]):
     override def apply(request: Request): UIO[Response] =
       UIO.succeed(Response.text("example"))

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
request-log formatting, and debug signature generation. Generate an scoverage
report for the backend with:

.. code-block:: console

   sbt clean "project backend" coverage test coverageReport

Coverage instrumentation is disabled for the root project and Scala.js frontend.
The generated HTML report is written below the backend ``target`` directory.

Packaging and deployment
------------------------

Build a deployment ZIP with:

.. code-block:: console

   sbt artifact

This performs a clean build and creates
``backend/target/webapptemplate-<version>.zip``. The archive contains the
application JAR, all runtime dependency JARs, ``prod.env``, and launchers for
Unix-like systems and Windows.

After extracting the archive, start it on Linux or macOS with:

.. code-block:: console

   sh runApp

Or on Windows with:

.. code-block:: doscon

   runApp.bat

Both launchers load ``prod.env`` before starting ``sgrv.be.Main``. A compatible
Java runtime must be installed on the deployment host.

Repository layout
-----------------

.. code-block:: text

   build.sbt
   project/Dependencies.scala
   project/plugins.sbt
   frontend/src/main/scala/sgrv/fe/Main.scala
   backend/src/main/scala/sgrv/be/Main.scala
   backend/src/main/scala/sgrv/be/core/
   backend/src/main/scala/sgrv/be/debug/
   backend/src/main/resources/web/
   backend/src/test/scala/
   scripts/stopapp.ps1

Security warning
----------------

The ``/debug`` response intentionally exposes sensitive diagnostic data,
including environment-variable values and potentially nginx configuration. Do
not make this route available to untrusted clients without adding appropriate
access controls or removing the module.
