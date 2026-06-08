package com.hypervolt.conduit.api

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import cats.syntax.all._
import com.comcast.ip4s._
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.AccessRoutes
import com.hypervolt.conduit.api.routes.AuditRoutes
import com.hypervolt.conduit.api.routes.CommerceRoutes
import com.hypervolt.conduit.api.routes.CreditRoutes
import com.hypervolt.conduit.api.routes.DealDeskRoutes
import com.hypervolt.conduit.api.routes.H6QRoutes
import com.hypervolt.conduit.api.routes.HealthRoutes
import com.hypervolt.conduit.api.routes.IntercompanyRoutes
import com.hypervolt.conduit.api.routes.DocumentRoutes
import com.hypervolt.conduit.api.routes.PricingRoutes
import com.hypervolt.conduit.api.routes.StripeWebhookRoutes
import com.hypervolt.conduit.config.AppConfig
import com.hypervolt.conduit.document.S3DocumentStorage
import com.hypervolt.conduit.config.EnvironmentConfig
import com.hypervolt.conduit.db.Transactor
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
      cfg <- Resource.eval(EnvironmentConfig.load[IO])
      _   <- Resource.eval(logger.info(s"Conduit API starting (env=${cfg.env})"))
      _   <- Resource.eval(FlywayInit.run[IO](cfg.db))
      _   <- Resource.eval(logger.info("Flyway migrations applied"))
      xa  <- Transactor.build[IO](cfg.db)
    } yield (cfg, xa)

  override def run: IO[Unit] =
    resources.use {
      case (cfg, xa) =>
        val auth           = new AuthService[IO](xa, devMode = cfg.env != "prod")
        val accessRoutes   = new AccessRoutes[IO](xa, auth).routes
        val pricingRoutes  = new PricingRoutes[IO](xa, auth).routes
        val commerceRoutes = new CommerceRoutes[IO](xa, auth).routes
        val dealDeskRoutes = new DealDeskRoutes[IO](xa, auth).routes
        val h6qRoutes      = new H6QRoutes[IO](xa, auth).routes
        val icRoutes       = new IntercompanyRoutes[IO](xa, auth).routes
        val creditRoutes   = new CreditRoutes[IO](xa, auth).routes
        val auditRoutes    = new AuditRoutes[IO](xa, auth).routes
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
        val app =
          Router(
            "/" -> (HealthRoutes
              .routes[
                IO
              ] <+> accessRoutes <+> pricingRoutes <+> commerceRoutes <+> dealDeskRoutes <+> h6qRoutes <+> icRoutes <+> creditRoutes <+> auditRoutes <+> stripeRoutes <+> documentRoutes)
          ).orNotFound
        val host      = Ipv4Address.fromString(cfg.http.host).getOrElse(ipv4"0.0.0.0")
        val apiPort   = Port.fromInt(cfg.http.port).getOrElse(port"8080")
        val adminPort = Port.fromInt(cfg.adminPort).getOrElse(port"9990")
        logger.info(s"Listening on api=$apiPort admin=$adminPort") *>
          (httpServer(host, apiPort, app), httpServer(host, adminPort, app)).tupled.useForever
    }
}
