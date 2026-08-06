package sgrv.be.auth

import zio.{System, Task, ZIO, ZLayer}

private[be] final case class OAuthConfig(clientId: String, clientSecret: String)
private[auth] final case class FirestoreConfig(projectId: String, databaseId: String, location: String)
private[be] final case class AppConfig(oauth: OAuthConfig, firestore: FirestoreConfig)

private[be] object AppConfig:
  def load: Task[AppConfig] =
    System.envs.flatMap(environment => ZIO.fromEither(fromEnvironment(environment)))

  val live: ZLayer[Any, Throwable, AppConfig] = ZLayer.fromZIO(load)

  private[auth] def fromEnvironment(environment: Map[String, String]): Either[IllegalArgumentException, AppConfig] =
    def required(name: String): Either[IllegalArgumentException, String] =
      environment.get(name).map(_.trim).filter(_.nonEmpty)
        .toRight(new IllegalArgumentException(s"Environment variable $name is not set or is empty; see prod.env"))

    for
      clientId     <- required("GOOGLE_OAUTH_CLIENT_ID")
      clientSecret <- required("GOOGLE_OAUTH_CLIENT_SECRET")
      projectId    <- required("GCP_PROJECT_ID")
      databaseId   <- required("FIRESTORE_DATABASE_ID")
      location     <- required("FIRESTORE_LOCATION")
    yield AppConfig(OAuthConfig(clientId, clientSecret), FirestoreConfig(projectId, databaseId, location))

