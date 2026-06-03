package com.hypervolt.conduit.config

import cats.effect.Sync
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

final case class DbConfig(host: String, port: Int, database: String, user: String, password: String) {
  def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"
}

final case class HttpConfig(host: String, port: Int)

final case class AppConfig(env: String, db: DbConfig, http: HttpConfig, adminPort: Int)

object EnvironmentConfig {

  def load[F[_]: Sync]: F[AppConfig] =
    Sync[F].delay(fromConfig(ConfigFactory.load()))

  def fromConfig(root: Config): AppConfig = {
    val hv    = root.getConfig("hypervolt")
    val db    = hv.getConfig("database")
    val http  = hv.getConfig("http")
    val admin = hv.getConfig("admin")
    AppConfig(
      env = hv.getString("env"),
      db = DbConfig(
        host = db.getString("host"),
        port = db.getInt("port"),
        database = db.getString("database"),
        user = db.getString("user"),
        password = db.getString("password")
      ),
      http = HttpConfig(http.getString("host"), http.getInt("port")),
      adminPort = admin.getInt("port")
    )
  }
}
