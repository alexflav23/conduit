package com.hypervolt.conduit.config

import cats.effect.Sync
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

final case class DbConfig(host: String, port: Int, database: String, user: String, password: String) {
  def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"
}

final case class HttpConfig(host: String, port: Int)

final case class PulsarConfig(serviceUrl: String)

final case class TigerBeetleConfig(cluster: Long, addresses: String)

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

// Stripe webhook (doc 13 §payments). An empty secret disables signature verification (dev/CI) — the inbound
// row is still recorded and the consumer settles it, so the path is exercisable without a live Stripe account.
final case class StripeConfig(webhookSecret: String) {
  def verifies: Boolean = webhookSecret.nonEmpty
}

// WORM document store (doc 17 §6). `endpoint` empty → the default AWS S3 client (instance role); set → a custom
// endpoint (LocalStack) with static creds. The consumer renders + stores invoice PDFs here.
final case class DocumentsConfig(bucket: String, endpoint: String, accessKey: String, secretKey: String) {
  def usesEndpoint: Boolean = endpoint.nonEmpty
}

// Google Workspace domain-gated sign-in (the ghost-busters pattern, enforced SERVER-side): the desk sends a
// Google ID token as the bearer; the API verifies signature (Google JWKS), audience (our OAuth client id) and
// the `hd` hosted-domain claim. Empty client id → the verifier is not wired (dev tokens only).
final case class GoogleAuthConfig(clientId: String, workspaceDomain: String) {
  def enabled: Boolean = clientId.nonEmpty
}

final case class AppConfig(
    env: String,
    db: DbConfig,
    http: HttpConfig,
    adminPort: Int,
    pulsar: PulsarConfig,
    tigerbeetle: TigerBeetleConfig,
    xero: XeroConfig,
    stripe: StripeConfig,
    documents: DocumentsConfig,
    googleAuth: GoogleAuthConfig
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
    val tb     = hv.getConfig("tigerbeetle")
    val xero   = hv.getConfig("xero")
    val stripe = hv.getConfig("stripe")
    val docs   = hv.getConfig("documents")
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
      tigerbeetle = TigerBeetleConfig(tb.getLong("cluster"), tb.getString("addresses")),
      xero = XeroConfig(
        clientId = xero.getString("client_id"),
        clientSecret = xero.getString("client_secret"),
        identityUrl = xero.getString("identity_url"),
        apiUrl = xero.getString("api_url"),
        tenantId = Option(xero.getString("tenant_id")).map(_.trim).filter(_.nonEmpty),
        scope = xero.getString("scope")
      ),
      stripe = StripeConfig(webhookSecret = stripe.getString("webhook_secret")),
      documents = DocumentsConfig(
        bucket = docs.getString("bucket"),
        endpoint = docs.getString("endpoint"),
        accessKey = docs.getString("access_key"),
        secretKey = docs.getString("secret_key")
      ),
      googleAuth = GoogleAuthConfig(
        clientId = hv.getConfig("auth").getString("google_client_id"),
        workspaceDomain = hv.getConfig("auth").getString("workspace_domain")
      )
    )
  }
}
