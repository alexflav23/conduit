package com.hypervolt.conduit.consumer

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.accounting.AccountingConsumer
import com.hypervolt.conduit.accounting.InvoiceDispatcher
import com.hypervolt.conduit.accounting.XeroAccountingConsumer
import com.hypervolt.conduit.config.AppConfig
import com.hypervolt.conduit.config.EnvironmentConfig
import com.hypervolt.conduit.db.Transactor
import com.hypervolt.conduit.event.OutboxRelay
import com.hypervolt.conduit.event.PulsarEventPublisher
import com.hypervolt.conduit.ledger.TigerBeetleClient
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.payment.PaymentService
import com.hypervolt.conduit.payment.StripeInboundProcessor
import com.hypervolt.conduit.payment.StripePaymentHandler
import com.hypervolt.conduit.pulsar.PulsarUtils
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.tigerbeetle.{Client => TbClient}
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

  private val resources: Resource[IO, (AppConfig, HikariTransactor[IO], PulsarClient, Client[IO], TbClient)] =
    for {
      cfg    <- Resource.eval(EnvironmentConfig.load[IO])
      _      <- Resource.eval(logger.info(s"Conduit consumer starting (env=${cfg.env})"))
      xa     <- Transactor.build[IO](cfg.db)
      pulsar <- PulsarUtils.makeClient[IO](cfg.pulsar.serviceUrl)
      http   <- EmberClientBuilder.default[IO].build
      tb     <- TigerBeetleClient.make[IO](cfg.tigerbeetle.cluster, cfg.tigerbeetle.addresses)
    } yield (cfg, xa, pulsar, http, tb)

  override def run: IO[Unit] =
    resources.use {
      case (cfg, xa, pulsar, http, tb) =>
        XeroAccountingConsumer.build[IO](http, cfg.xero).flatMap { (accounting: AccountingConsumer[IO]) =>
          val ledger          = TigerBeetleLedger.fromClient[IO](tb)
          val dispatcher      = new InvoiceDispatcher[IO](xa, accounting)
          val xeroConsumer    = new XeroInvoiceConsumer[IO](pulsar, dispatcher)
          val revConsumer     = new RevenueRecognitionConsumer[IO](pulsar, new RevenueRecognitionService[IO](xa, ledger))
          val stripeHandler   = new StripePaymentHandler[IO](new PaymentService[IO](xa, ledger))
          val stripeProcessor = new StripeInboundProcessor[IO](xa, stripeHandler)
          val stripeLoop      = (stripeProcessor.runOnce() *> IO.sleep(2.second)).foreverM
          PulsarEventPublisher.create[IO](pulsar).flatMap { publisher =>
            val relay     = new OutboxRelay[IO](xa, publisher)
            val relayLoop = (relay.runOnce() *> IO.sleep(1.second)).foreverM
            logger.info("Consumer running: outbox relay + Xero invoice + revenue recognition + Stripe settlement") *>
              List(relayLoop, xeroConsumer.runForever, revConsumer.runForever, stripeLoop).parSequence_
          }
        }
    }
}
