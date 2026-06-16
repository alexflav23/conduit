package com.hypervolt.conduit.api

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import cats.effect.std.Dispatcher
import cats.syntax.all._
import com.comcast.ip4s._
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.GoogleTokenVerifier
import com.hypervolt.conduit.api.auth.KeycloakJwtVerifier
import com.hypervolt.conduit.api.routes.AccessRoutes
import com.hypervolt.conduit.api.routes.ActivationRoutes
import com.hypervolt.conduit.api.routes.AuditRoutes
import com.hypervolt.conduit.api.routes.CommerceRoutes
import com.hypervolt.conduit.api.routes.CreditRoutes
import com.hypervolt.conduit.api.routes.CrmRoutes
import com.hypervolt.conduit.api.routes.DealDeskRoutes
import com.hypervolt.conduit.api.routes.ForecastRunRoutes
import com.hypervolt.conduit.api.routes.H6QRoutes
import com.hypervolt.conduit.api.routes.HealthRoutes
import com.hypervolt.conduit.api.routes.IntercompanyRoutes
import com.hypervolt.conduit.api.routes.DocumentRoutes
import com.hypervolt.conduit.api.routes.InvoiceVoidRoutes
import com.hypervolt.conduit.api.routes.OrderLifecycleRoutes
import com.hypervolt.conduit.api.routes.PricingRoutes
import com.hypervolt.conduit.api.routes.ShelfDetailRoutes
import com.hypervolt.conduit.api.routes.StripeWebhookRoutes
import com.hypervolt.conduit.config.AppConfig
import com.hypervolt.conduit.document.S3DocumentStorage
import com.hypervolt.conduit.config.EnvironmentConfig
import com.hypervolt.conduit.db.Transactor
import com.hypervolt.conduit.logging.OtelAppender
import com.hypervolt.conduit.metrics.ConduitMetrics
import com.hypervolt.conduit.metrics.GlobalMetrics
import com.hypervolt.conduit.metrics.MetricsBuilder
import doobie.hikari.HikariTransactor
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Router
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Main extends IOApp.Simple {

  private val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  private def httpServer(host: Ipv4Address, port: Port, app: HttpApp[IO]): Resource[IO, Unit] =
    EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(app)
      .build
      .void

  private val resources: Resource[IO, (AppConfig, HikariTransactor[IO])] =
    for {
      cfg        <- Resource.eval(EnvironmentConfig.load[IO])
      _          <- Resource.eval(logger.info(s"Conduit API starting (env=${cfg.env})"))
      _          <- Resource.eval(FlywayInit.run[IO](cfg.db))
      _          <- Resource.eval(logger.info("Flyway migrations applied"))
      xa         <- Transactor.build[IO](cfg.db)
      dispatcher <- Dispatcher.parallel[IO]
      otel <- GlobalMetrics.buildResource[IO](
        "conduit"
      ) // Prometheus exporter on :9464 (PROMETHEUS_PORT) — the estate scrapes it
      _ <- Resource.eval(IO.delay(ApiMetrics.install(otel.getMeter("tapir")))) // per-endpoint HTTP metrics
      _ <- Resource.eval(IO.delay(OtelAppender.register(otel.getMeterProvider(), "logs_count_conduit")))
      _ <- Resource.eval(
        new ConduitMetrics[IO](xa, new MetricsBuilder(otel.getMeter("conduit"), "conduit"), dispatcher).register
      )
      _ <- Resource.eval(logger.info("Metrics exporter started (:9464)"))
      // git-snapshot ingest (doc 26 §3a): load any NDJSON snapshots committed under INGEST_DIR — deterministic +
      // idempotent, so checkout → docker compose up → seeded, and every re-boot converges to the same state.
      loaded <- Resource.eval(
        new com.hypervolt.conduit.ingest.SnapshotLoader[IO](xa)
          .loadAll(java.nio.file.Paths.get(sys.env.getOrElse("INGEST_DIR", "ingest")))
      )
      _ <- Resource.eval(logger.info(s"Snapshot ingest: $loaded rows loaded"))
      // Boot ignition (idempotent): replay the ingested history through the production engines so a fresh env
      // reconverges to the live state (lots, periods, exposure + dispatch.created events the consumer recognises).
      // IGNITE=false disables it (e.g. when operating the books manually).
      _ <- Resource.eval(
        if (sys.env.getOrElse("IGNITE", "true").toBoolean)
          new com.hypervolt.conduit.ingest.IgnitionService[IO](xa).ignite.flatMap(s => logger.info(s"Ignition: $s"))
        else logger.info("Ignition: disabled (IGNITE=false)")
      )
    } yield (cfg, xa)

  override def run: IO[Unit] =
    resources.use {
      case (cfg, xa) =>
        val googleVerifier = Option.when(cfg.googleAuth.enabled)(
          new GoogleTokenVerifier[IO](
            new com.auth0.jwk.GuavaCachedJwkProvider(
              new com.auth0.jwk.UrlJwkProvider(java.net.URI.create(GoogleTokenVerifier.JwksUrl).toURL())
            ),
            cfg.googleAuth.clientId,
            cfg.googleAuth.workspaceDomain
          )
        )
        val keycloakVerifier = Option.when(cfg.keycloak.enabled)(
          new KeycloakJwtVerifier[IO](
            new com.auth0.jwk.GuavaCachedJwkProvider(
              new com.auth0.jwk.UrlJwkProvider(java.net.URI.create(cfg.keycloak.jwksUrl).toURL())
            ),
            cfg.keycloak.issuer,
            cfg.keycloak.audience
          )
        )
        val auth              = new AuthService[IO](xa, devMode = cfg.env != "prod", google = googleVerifier, keycloak = keycloakVerifier)
        val accessRoutes      = new AccessRoutes[IO](xa, auth).routes
        val pricingRoutes     = new PricingRoutes[IO](xa, auth).routes
        val commerceRoutes    = new CommerceRoutes[IO](xa, auth).routes
        val crmRoutes         = new CrmRoutes[IO](xa, auth).routes
        val activationRoutes  = new ActivationRoutes[IO](xa, auth).routes
        val shelfDetailRoutes = new ShelfDetailRoutes[IO](xa, auth).routes
        val dealDeskRoutes    = new DealDeskRoutes[IO](xa, auth).routes
        val h6qRoutes         = new H6QRoutes[IO](xa, auth).routes
        val forecastRunRoutes = new ForecastRunRoutes[IO](xa, auth).routes
        val icRoutes          = new IntercompanyRoutes[IO](xa, auth).routes
        val creditRoutes      = new CreditRoutes[IO](xa, auth).routes
        val auditRoutes       = new AuditRoutes[IO](xa, auth).routes
        val stripeVerifier = Option.when(cfg.stripe.verifies)(
          new com.hypervolt.conduit.payment.StripeSignatureVerifier(cfg.stripe.webhookSecret)
        )
        val stripeRoutes = new StripeWebhookRoutes[IO](xa, stripeVerifier).routes
        val s3Client =
          if (cfg.documents.usesEndpoint)
            S3DocumentStorage.endpointClient(cfg.documents.endpoint, cfg.documents.accessKey, cfg.documents.secretKey)
          else S3DocumentStorage.awsClient
        val documentRoutes =
          new DocumentRoutes[IO](xa, auth, new S3DocumentStorage[IO](s3Client, cfg.documents.bucket)).routes
        val attachmentRoutes =
          new com.hypervolt.conduit.api.routes.AttachmentRoutes[IO](
            xa,
            auth,
            new S3DocumentStorage[IO](s3Client, cfg.documents.bucket)
          ).routes
        val voidRoutes        = new InvoiceVoidRoutes[IO](xa, auth).routes
        val lifecycleRoutes   = new OrderLifecycleRoutes[IO](xa, auth).routes
        val taxRoutes         = new com.hypervolt.conduit.api.routes.TaxRoutes[IO](xa, auth).routes
        val procurementRoutes = new com.hypervolt.conduit.api.routes.ProcurementRoutes[IO](xa, auth).routes
        val structureRoutes   = new com.hypervolt.conduit.api.routes.EntityStructureRoutes[IO](xa, auth).routes
        val returnRoutes      = new com.hypervolt.conduit.api.routes.ReturnRoutes[IO](xa, auth).routes
        val treasuryRoutes    = new com.hypervolt.conduit.api.routes.TreasuryRoutes[IO](xa, auth).routes
        val activationStream  = new com.hypervolt.conduit.api.routes.ActivationStreamRoutes[IO](xa, auth).routes
        // the Tamper Sandbox shares the dev-token gate: it exists only outside prod (doc 31 §2.5)
        val proofRoutes =
          new com.hypervolt.conduit.api.routes.ProofRoutes[IO](xa, auth, tamperEnabled = cfg.env != "prod").routes
        // PII key-encryption-key: from the secrets-injected PII_KEK (base64, 32 bytes) in prod; dev falls back to a
        // fixed local key (doc 19 §B.1/§B.3). The KEK only WRAPS per-subject DEKs; it never touches plaintext PII.
        val piiKek = sys.env
          .get("PII_KEK")
          .map(k => java.util.Base64.getDecoder.decode(k))
          .getOrElse(com.hypervolt.conduit.privacy.CryptoShred.devKey)
        val privacyRoutes =
          new com.hypervolt.conduit.api.routes.PrivacyRoutes[IO](
            xa,
            auth,
            new com.hypervolt.conduit.privacy.CryptoShred(piiKek)
          ).routes
        val app =
          Router(
            "/" -> (HealthRoutes
              .routes[
                IO
              ] <+> accessRoutes <+> pricingRoutes <+> commerceRoutes <+> crmRoutes <+> activationRoutes <+> shelfDetailRoutes <+> dealDeskRoutes <+> h6qRoutes <+> forecastRunRoutes <+> icRoutes <+> creditRoutes <+> auditRoutes <+> stripeRoutes <+> documentRoutes <+> attachmentRoutes <+> voidRoutes <+> lifecycleRoutes <+> taxRoutes <+> procurementRoutes <+> structureRoutes <+> returnRoutes <+> treasuryRoutes <+> activationStream <+> privacyRoutes <+> proofRoutes)
          ).orNotFound
        val host      = Ipv4Address.fromString(cfg.http.host).getOrElse(ipv4"0.0.0.0")
        val apiPort   = Port.fromInt(cfg.http.port).getOrElse(port"8080")
        val adminPort = Port.fromInt(cfg.adminPort).getOrElse(port"9990")
        // P2.5 (doc 19 §B.4): per-principal rate limiting on the API port (admin/health unthrottled). Generous
        // defaults so normal use is never touched; a runaway caller 429s before it can starve core.
        com.hypervolt.conduit.ratelimit.RateLimiter.create[IO](capacity = 200.0, refillPerSec = 100.0).flatMap {
          limiter =>
            val limitedApp = RateLimitMiddleware(limiter)(app)
            logger.info(s"Listening on api=$apiPort admin=$adminPort") *>
              (httpServer(host, apiPort, limitedApp), httpServer(host, adminPort, app)).tupled.useForever
        }
    }
}
