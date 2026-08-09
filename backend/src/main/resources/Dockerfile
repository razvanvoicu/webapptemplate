# Built by `sbt artifact` from a staging directory containing app.jar, lib/*.jar, prod.env, runApp, and (for
# local testing outside a GCP platform's own workload identity) application_default_credentials.json.
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
COPY application_default_credentials.json /root/.config/gcloud/application_default_credentials.json

RUN chmod +x runApp

# A process bound to 127.0.0.1 (this app's default) is unreachable from outside the container regardless of
# published ports; 0.0.0.0 makes it reachable through Docker's own port mapping.
ENV BIND_ADDRESS=0.0.0.0
# Not itself a secret: a filesystem path, matching Google's own well-known ADC location. The actual credential
# is in the COPY'd file above, already covered by the SECURITY note at the top of this file.
ENV GOOGLE_APPLICATION_CREDENTIALS=/root/.config/gcloud/application_default_credentials.json

EXPOSE 8888

ENTRYPOINT ["./runApp"]
