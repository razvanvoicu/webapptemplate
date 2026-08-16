# Built by `sbt artifact` from a staging directory containing app.jar, lib/*.jar, prod.env, runApp, and an adc/
# directory that optionally contains application_default_credentials.json for local testing outside GCP.
#
# SECURITY: the resulting image bakes in OAuth/admin secrets (via prod.env) and, if present, a Google Application
# Default Credentials file. Treat the image itself as a secret-bearing artifact: do not push it to a public
# registry.
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY app.jar ./
COPY lib/ ./lib/
COPY prod.env ./
COPY runApp ./
COPY adc/ /root/.config/gcloud/

RUN chmod +x runApp

# A process bound to 127.0.0.1 (this app's default) is unreachable from outside the container regardless of
# published ports; 0.0.0.0 makes it reachable through Docker's own port mapping.
ENV BIND_ADDRESS=0.0.0.0

# Do not set GOOGLE_APPLICATION_CREDENTIALS here. If adc/ contains the optional file, Google's libraries find it
# at their well-known gcloud path. If it does not, Application Default Credentials can continue to workload
# identity or another provider instead of failing on a forced, nonexistent file path.

EXPOSE 8888

# Docker sends this directly to the JVM: the exec-form entrypoint starts runApp without an intermediate Docker
# shell, and runApp replaces itself with Java after loading prod.env.
STOPSIGNAL SIGTERM
ENTRYPOINT ["/app/runApp"]
