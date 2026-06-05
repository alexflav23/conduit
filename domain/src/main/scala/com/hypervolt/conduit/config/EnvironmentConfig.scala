package com.hypervolt.conduit.config

import cats.effect.Sync
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

final case class DbConfig(host: String, port: Int, database: String, user: String, password: String) {
  def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"
}

final case class HttpConfig(host: String, port: Int)

final case class PulsarConfig(serviceUrl: String)

// Xero accounting consumer (doc 13/07 M13). client-credentials OAuth2. `enabled` is false when the client id is
// missing/placeholder → the consumer wires a local no-op so dev/CI never call Xero.
final case class XeroConfig(
    clientId: String,
    clientSecret: String,
    identityUrl: String,
    apiUrl: String,
    tenantId: Option[String],
    scope: String
) {
  def enabled: Boolean =
    clientId.nonEmpty && !clientId.startsWith("your-") && clientId != "default" && clientId != "changeme"
}

final case class AppConfig(
    env: String,
    db: DbConfig,
    http: HttpConfig,
    adminPort: Int,
    pulsar: PulsarConfig,
    xero: XeroConfig
)

object EnvironmentConfig {

  def load[F[_]: Sync]: F[AppConfig] =
    Sync[F].delay(fromConfig(ConfigFactory.load()))

  def fromConfig(root: Config): AppConfig = {
    val hv     = root.getConfig("hypervolt")
    val db     = hv.getConfig("database")
    val http   = hv.getConfig("http")
    val admin  = hv.getConfig("admin")
    val pulsar = hv.getConfig("pulsar")
    val xero   = hv.getConfig("xero")
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
      adminPort = admin.getInt("port"),
      pulsar = PulsarConfig(pulsar.getString("service_url")),
      xero = XeroConfig(
        clientId = xero.getString("client_id"),
        clientSecret = xero.getString("client_secret"),
        identityUrl = xero.getString("identity_url"),
        apiUrl = xero.getString("api_url"),
        tenantId = Option(xero.getString("tenant_id")).map(_.trim).filter(_.nonEmpty),
        scope = xero.getString("scope")
      )
    )
  }
}
