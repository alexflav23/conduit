package com.hypervolt.conduit.consumer

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import com.hypervolt.conduit.accounting.AccountingConsumer
import com.hypervolt.conduit.accounting.InvoiceDispatcher
import com.hypervolt.conduit.accounting.XeroAccountingConsumer
import com.hypervolt.conduit.config.AppConfig
import com.hypervolt.conduit.config.EnvironmentConfig
import com.hypervolt.conduit.db.Transactor
import com.hypervolt.conduit.event.OutboxRelay
import com.hypervolt.conduit.event.PulsarEventPublisher
import com.hypervolt.conduit.pulsar.PulsarUtils
import doobie.hikari.HikariTransactor
import org.apache.pulsar.client.api.PulsarClient
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scala.concurrent.duration._

// Conduit consumer process (doc 01 §4). Owns the outbox relay (Postgres outbox → Pulsar) and the downstream
// consumers. M13: the Xero invoice consumer turns `order.invoiced` into a Xero invoice via the swappable
// AccountingConsumer. The accounting system is a CONSUMER — TigerBeetle stays the system of record.
object Main extends IOApp.Simple {

  private val logger = Slf4jLogger.getLogger[IO]

  private val resources: Resource[IO, (AppConfig, HikariTransactor[IO], PulsarClient, Client[IO])] =
    for {
      cfg    <- Resource.eval(EnvironmentConfig.load[IO])
      _      <- Resource.eval(logger.info(s"Conduit consumer starting (env=${cfg.env})"))
      xa     <- Transactor.build[IO](cfg.db)
      pulsar <- PulsarUtils.makeClient[IO](cfg.pulsar.serviceUrl)
      http   <- EmberClientBuilder.default[IO].build
    } yield (cfg, xa, pulsar, http)

  override def run: IO[Unit] =
    resources.use {
      case (cfg, xa, pulsar, http) =>
        XeroAccountingConsumer.build[IO](http, cfg.xero).flatMap { (accounting: AccountingConsumer[IO]) =>
          val dispatcher   = new InvoiceDispatcher[IO](xa, accounting)
          val xeroConsumer = new XeroInvoiceConsumer[IO](pulsar, dispatcher)
          PulsarEventPublisher.create[IO](pulsar).flatMap { publisher =>
            val relay     = new OutboxRelay[IO](xa, publisher)
            val relayLoop = (relay.runOnce() *> IO.sleep(1.second)).foreverM
            logger.info("Consumer running: outbox relay + Xero invoice consumer") *>
              IO.race(relayLoop, xeroConsumer.runForever).void
          }
        }
    }
}
