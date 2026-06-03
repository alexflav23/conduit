package com.hypervolt.conduit.api

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import cats.syntax.all._
import com.comcast.ip4s._
import com.hypervolt.conduit.api.routes.HealthRoutes
import com.hypervolt.conduit.config.AppConfig
import com.hypervolt.conduit.config.EnvironmentConfig
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

  private val resources: Resource[IO, AppConfig] =
    for {
      cfg <- Resource.eval(EnvironmentConfig.load[IO])
      _   <- Resource.eval(logger.info(s"Conduit API starting (env=${cfg.env})"))
      _   <- Resource.eval(FlywayInit.run[IO](cfg.db))
      _   <- Resource.eval(logger.info("Flyway migrations applied"))
    } yield cfg

  override def run: IO[Unit] =
    resources.use { cfg =>
      val app       = Router("/" -> HealthRoutes.routes[IO]).orNotFound
      val host      = Ipv4Address.fromString(cfg.http.host).getOrElse(ipv4"0.0.0.0")
      val apiPort   = Port.fromInt(cfg.http.port).getOrElse(port"8080")
      val adminPort = Port.fromInt(cfg.adminPort).getOrElse(port"9990")
      logger.info(s"Listening on api=$apiPort admin=$adminPort") *>
        (httpServer(host, apiPort, app), httpServer(host, adminPort, app)).tupled.useForever
    }
}
