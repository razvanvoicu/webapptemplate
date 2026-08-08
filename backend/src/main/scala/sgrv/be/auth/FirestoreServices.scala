package sgrv.be.auth

import com.google.api.gax.rpc.NotFoundException
import com.google.cloud.Timestamp
import com.google.cloud.firestore.v1.FirestoreAdminClient
import com.google.cloud.firestore.{Firestore, FirestoreOptions}
import com.google.firestore.admin.v1.{CreateDatabaseRequest, Database, GetDatabaseRequest}
import java.time.Instant
import zio.{Task, ZIO, ZLayer}

private[be] trait DatabaseAdmin:
  def ensureDatabase: Task[Unit]

private[be] object DatabaseAdmin:
  def ensureDatabase: ZIO[DatabaseAdmin, Throwable, Unit] =
    ZIO.serviceWithZIO[DatabaseAdmin](_.ensureDatabase)

  val live: ZLayer[AppConfig, Throwable, DatabaseAdmin] =
    ZLayer.scoped:
      for
        config <- ZIO.service[AppConfig]
        client <- ZIO.acquireRelease(ZIO.attemptBlocking(FirestoreAdminClient.create()))(
          value => ZIO.attemptBlocking(value.close()).ignore
        )
      yield Live(config.firestore, client)

  private final case class Live(config: FirestoreConfig, client: FirestoreAdminClient) extends DatabaseAdmin:
    override def ensureDatabase: Task[Unit] =
      val name = s"projects/${config.projectId}/databases/${config.databaseId}"
      ZIO.attemptBlocking(client.getDatabase(GetDatabaseRequest.newBuilder().setName(name).build())).unit.catchSome:
        case _: NotFoundException =>
          GoogleFuture.fromApiFuture:
            client.createDatabaseAsync(
              CreateDatabaseRequest.newBuilder()
                .setParent(s"projects/${config.projectId}")
                .setDatabaseId(config.databaseId)
                .setDatabase(
                  Database.newBuilder()
                    .setType(Database.DatabaseType.FIRESTORE_NATIVE)
                    .setLocationId(config.location)
                ).build()
            )
          .unit

trait SessionStore:
  def create(
      sessionKey: String,
      user: SessionUser,
      createdAt: Instant,
      expiresAt: Instant,
      refreshToken: Option[String]
  ): Task[Unit]
  def find(sessionKey: String, now: Instant): Task[Option[SessionUser]]

private[be] object SessionStore:
  private val accessCollection = "Access"

  def create(
      sessionKey: String,
      user: SessionUser,
      createdAt: Instant,
      expiresAt: Instant,
      refreshToken: Option[String]
  ): ZIO[SessionStore, Throwable, Unit] =
    ZIO.serviceWithZIO[SessionStore](_.create(sessionKey, user, createdAt, expiresAt, refreshToken))

  def find(sessionKey: String, now: Instant): ZIO[SessionStore, Throwable, Option[SessionUser]] =
    ZIO.serviceWithZIO[SessionStore](_.find(sessionKey, now))

  val live: ZLayer[AppConfig, Throwable, SessionStore] =
    ZLayer.scoped:
      for
        config <- ZIO.service[AppConfig]
        firestore <- ZIO.acquireRelease(
          ZIO.attemptBlocking:
            FirestoreOptions.newBuilder()
              .setProjectId(config.firestore.projectId)
              .setDatabaseId(config.firestore.databaseId)
              .build()
              .getService
        )(value => ZIO.attemptBlocking(value.close()).ignore)
      yield Live(firestore)

  private final case class Live(firestore: Firestore) extends SessionStore:
    override def create(
        sessionKey: String,
        user: SessionUser,
        createdAt: Instant,
        expiresAt: Instant,
        refreshToken: Option[String]
    ): Task[Unit] =
      for
        fields <- ZIO.attempt:
          val values = new java.util.HashMap[String, AnyRef]
          values.put("email", user.email)
          values.put("name", user.name)
          values.put("createdAt", timestamp(createdAt))
          values.put("sessionKey", sessionKey)
          values.put("expiresAt", timestamp(expiresAt))
          refreshToken.foreach(value => values.put("refreshToken", value))
          values
        _ <- GoogleFuture.fromApiFuture(
          firestore.collection(accessCollection).document(sessionKey).create(fields)
        )
      yield ()

    override def find(sessionKey: String, now: Instant): Task[Option[SessionUser]] =
      normalized(sessionKey) match
        case None => ZIO.none
        case Some(key) =>
          GoogleFuture.fromApiFuture(firestore.collection(accessCollection).document(key).get()).map: snapshot =>
            Option.when(snapshot.exists)(snapshot).flatMap: session =>
              val storedKey    = normalized(session.getString("sessionKey"))
              val expiresAt    = Option(session.getTimestamp("expiresAt")).map(_.toDate.toInstant)
              val email        = normalized(session.getString("email"))
              val name         = normalized(session.getString("name"))
              val refreshToken = normalized(session.getString("refreshToken"))
              if storedKey.contains(key) && expiresAt.exists(now.isBefore) then
                email.map(address => SessionUser(address, name.getOrElse(address), refreshToken))
              else None

  private def normalized(value: String): Option[String] =
    Option(value).map(_.trim).filter(_.nonEmpty)

  private def timestamp(instant: Instant): Timestamp =
    Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond, instant.getNano)
