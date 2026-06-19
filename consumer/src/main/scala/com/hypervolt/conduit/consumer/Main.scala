package com.hypervolt.conduit.consumer

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import cats.effect.std.Dispatcher
import cats.syntax.all._
import com.hypervolt.conduit.accounting.AccountingConsumer
import com.hypervolt.conduit.accounting.InvoiceDispatcher
import com.hypervolt.conduit.accounting.XeroAccountingConsumer
import com.hypervolt.conduit.config.AppConfig
import com.hypervolt.conduit.config.EnvironmentConfig
import com.hypervolt.conduit.db.Transactor
import com.hypervolt.conduit.document.DocumentService
import com.hypervolt.conduit.document.FopDocumentRenderer
import com.hypervolt.conduit.document.S3DocumentStorage
import com.hypervolt.conduit.event.OutboxRelay
import com.hypervolt.conduit.event.PulsarEventPublisher
import com.hypervolt.conduit.event.PulsarInboundPublisher
import com.hypervolt.conduit.ingest.HttpHubSpotApi
import com.hypervolt.conduit.ingest.HttpMrpeasyApi
import com.hypervolt.conduit.ingest.HubSpotConnector
import com.hypervolt.conduit.ingest.MrpeasyConnector
import com.hypervolt.conduit.ingest.InboundRelay
import com.hypervolt.conduit.ingest.IngestRunner
import com.hypervolt.conduit.ingest.IngestScheduler
import com.hypervolt.conduit.ingest.IngestSink
import com.hypervolt.conduit.ingest.SnapshotLoader
import com.hypervolt.conduit.ingest.SyncStateRepo
import com.hypervolt.conduit.ledger.TigerBeetleClient
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.logging.OtelAppender
import com.hypervolt.conduit.metrics.ConduitMetrics
import com.hypervolt.conduit.metrics.GlobalMetrics
import com.hypervolt.conduit.metrics.MetricsBuilder
import com.hypervolt.conduit.payment.PaymentService
import com.hypervolt.conduit.payment.StripeInboundProcessor
import com.hypervolt.conduit.payment.StripePaymentHandler
import com.hypervolt.conduit.pulsar.PulsarUtils
import com.hypervolt.conduit.revenue.InvoiceReversalService
import com.hypervolt.conduit.revenue.InvoiceVoidProcessor
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
      cfg               <- Resource.eval(EnvironmentConfig.load[IO])
      _                 <- Resource.eval(logger.info(s"Conduit consumer starting (env=${cfg.env})"))
      xa                <- Transactor.build[IO](cfg.db)
      pulsar            <- PulsarUtils.makeClient[IO](cfg.pulsar.serviceUrl)
      http              <- EmberClientBuilder.default[IO].build
      tb                <- TigerBeetleClient.make[IO](cfg.tigerbeetle.cluster, cfg.tigerbeetle.addresses)
      metricsDispatcher <- Dispatcher.parallel[IO]
      otel <-
        GlobalMetrics
          .buildResource[IO]("conduit_consumer", 9465) // Prometheus exporter on :9465 — the estate scrapes it
      _ <- Resource.eval(IO.delay(OtelAppender.register(otel.getMeterProvider(), "logs_count_conduit_consumer")))
      _ <- Resource.eval(
        new ConduitMetrics[IO](
          xa,
          new MetricsBuilder(otel.getMeter("conduit_consumer"), "conduit_consumer"),
          metricsDispatcher
        ).register
      )
      _ <- Resource.eval(logger.info("Metrics exporter started (:9465)"))
    } yield (cfg, xa, pulsar, http, tb)

  override def run: IO[Unit] =
    resources.use {
      case (cfg, xa, pulsar, http, tb) =>
        XeroAccountingConsumer.build[IO](http, cfg.xero).flatMap { (accounting: AccountingConsumer[IO]) =>
          val ledger = TigerBeetleLedger.fromClient[IO](tb)
          // shadow dual-run (doc 33 §5): mute the outbound Xero push/void when hypervolt.shadow is on.
          val shadowGuard          = com.hypervolt.conduit.shadow.ShadowGuard[IO](xa, cfg.shadow)
          val dispatcher           = new InvoiceDispatcher[IO](xa, accounting, shadow = shadowGuard)
          val xeroConsumer         = new XeroInvoiceConsumer[IO](pulsar, dispatcher)
          val revConsumer          = new RevenueRecognitionConsumer[IO](pulsar, new RevenueRecognitionService[IO](xa, ledger))
          val stripeHandler        = new StripePaymentHandler[IO](new PaymentService[IO](xa, ledger))
          val stripeProcessor      = new StripeInboundProcessor[IO](xa, stripeHandler)
          val stripeLoop: IO[Unit] = (stripeProcessor.runOnce() *> IO.sleep(2.second)).foreverM
          val s3Client =
            if (cfg.documents.usesEndpoint)
              S3DocumentStorage.endpointClient(cfg.documents.endpoint, cfg.documents.accessKey, cfg.documents.secretKey)
            else S3DocumentStorage.awsClient
          val docService = new DocumentService[IO](
            xa,
            new FopDocumentRenderer[IO],
            new S3DocumentStorage[IO](s3Client, cfg.documents.bucket)
          )
          val docConsumer = new DocumentGenerationConsumer[IO](pulsar, docService)
          val voidProcessor = new InvoiceVoidProcessor[IO](
            xa,
            new InvoiceReversalService[IO](xa, ledger),
            new PaymentService[IO](xa, ledger)
          )
          val voidConsumer = new InvoiceVoidConsumer[IO](pulsar, voidProcessor)
          val vatRemitConsumer =
            new VatRemittanceConsumer[IO](pulsar, new com.hypervolt.conduit.tax.VatRemittanceService[IO](xa, ledger))
          val piiTombstoneConsumer =
            new PiiShreddedConsumer[IO](pulsar, new com.hypervolt.conduit.privacy.PiiTombstoneService[IO](xa))
          val rebateAccrualConsumer =
            new RebateAccrualConsumer[IO](
              pulsar,
              new com.hypervolt.conduit.pricing.RebateAccrualService[IO](xa, ledger)
            )
          val placementConsumer =
            new PlacementConsumer[IO](
              pulsar,
              xa,
              new com.hypervolt.conduit.warranty.ActivationService[IO](xa),
              new com.hypervolt.conduit.forecast.RunwayService[IO](xa)
            )
          val returnConsumer =
            new ReturnConsumer[IO](pulsar, new com.hypervolt.conduit.returns.ReturnService[IO](xa, ledger))
          val openingInvConsumer =
            new OpeningInventoryConsumer[IO](
              pulsar,
              xa,
              new com.hypervolt.conduit.migration.MigrationService[IO](xa, ledger)
            )
          val commissionConsumer =
            new CommissionConsumer[IO](
              pulsar,
              new com.hypervolt.conduit.commission.CommissionAccrualService[IO](xa, ledger)
            )
          val commitmentConsumer =
            new OrderCommitmentConsumer[IO](pulsar, new com.hypervolt.conduit.order.OrderCommitmentService[IO](xa))
          // P2.6: the notification delivery relay — routes pending external notifications through the channel
          // (logging stand-in until SES/FCM creds land), shadow-aware (email/push muted in the dual-run).
          val notifyDelivery =
            new com.hypervolt.conduit.notification.NotificationDelivery[IO](
              xa,
              com.hypervolt.conduit.notification.NotificationChannel.logging[IO],
              shadowGuard
            )
          val notifyLoop: IO[Unit] = (notifyDelivery.deliverPending() *> IO.sleep(5.second)).foreverM
          // The forecast engine's rolling-origin cycle (doc 26 §5–6) as a background job: calendar-derived
          // origins fitted → scored → materialized → live-published every 6h, each capturing its censored
          // depletion snapshot. Activations already feed account_forecast_state via the placement consumer; this
          // closes the loop so run history + depletion deltas accrue automatically — no external script.
          // Shadow-validation harness (doc 33 §5): re-run the discrepancy battery on a cycle so the triage queue
          // tracks live state. The cutover gate is a sustained window with zero open money/unit findings.
          val shadowValidation = new com.hypervolt.conduit.shadow.ShadowValidationService[IO](xa)
          val freeShipments    = new com.hypervolt.conduit.shadow.FreeShipmentService[IO](xa)
          val shadowLoop: IO[Unit] =
            (shadowValidation.runAll(None, cfg.shadow).attempt.void *>
              freeShipments.rebuild.attempt.void *> IO.sleep(6.hours)).foreverM
          val forecastCycle = new com.hypervolt.conduit.forecast.ForecastCycle[IO](xa)
          val forecastLoop: IO[Unit] =
            (IO(java.time.LocalDate.now())
              .flatMap(forecastCycle.runOnce)
              .flatMap(_ => logger.info("forecast-cycle: rolling-origin run complete"))
              .handleErrorWith(e => logger.error(e)(s"forecast-cycle failed: ${e.getMessage}")) *>
              IO.sleep(6.hours)).foreverM
          // The H6Q bottom-up capture cycle (doc 12 §2.2): open the current ISO-week cycle and generate the
          // outstanding capture slots for owned forecastable accounts. Idempotent on the week code; runs on boot
          // and weekly thereafter. The statistical forecastLoop above is the TOP-DOWN engine — this is the spine
          // owners submit into.
          val forecastService = new com.hypervolt.conduit.forecast.ForecastService[IO](xa)
          val h6qCycleLoop: IO[Unit] =
            (IO(java.time.LocalDate.now())
              .flatMap(forecastService.openCycle(_, "weekly"))
              .flatMap { case (id, created) => logger.info(s"h6q-cycle: cycle $id open, $created outstanding slots") }
              .handleErrorWith(e => logger.error(e)(s"h6q-cycle failed: ${e.getMessage}")) *>
              IO.sleep(6.hours)).foreverM
          // NOTE: the historical invoice document backfill was removed — minting fresh WORM PDFs for sales already
          // invoiced in MRPeasy/Xero, billed to MRPeasy stub parties with placeholder line items, produced
          // meaningless documents. The DocumentService + FOP/WORM/gapless engine remain for invoices Conduit
          // actually issues going forward (resolved to the master account, with real line items).
          (PulsarEventPublisher.create[IO](pulsar), PulsarInboundPublisher.create[IO](pulsar)).tupled.flatMap {
            case (publisher, inboundPub) =>
              val relay               = new OutboxRelay[IO](xa, publisher)
              val relayLoop: IO[Unit] = (relay.runOnce() *> IO.sleep(1.second)).foreverM
              // S1 shadow-mode inbox: relay the durably-landed inbound rows (ingest_record) → conduit.inbound, and a
              // mapping consumer maps each through the SAME SnapshotLoader handlers as boot → engines + outbox.
              val inboundRelay                                    = new InboundRelay[IO](xa, inboundPub)
              val inboundRelayLoop: IO[Unit]                      = (inboundRelay.runOnce() *> IO.sleep(1.second)).foreverM
              val inboundConsumer                                 = new InboundMappingConsumer[IO](pulsar, xa, new SnapshotLoader[IO](xa))
              implicit val log: org.typelevel.log4cats.Logger[IO] = logger
              // S2 live connectors: drive the (already-built) connectors into the durable inbox on a cadence.
              // HubSpot lights up only when a token is configured (else dormant — the house seam pattern).
              val ingestScheduler =
                new IngestScheduler[IO](new IngestRunner[IO](new SyncStateRepo[IO](xa)), new IngestSink[IO](xa))
              val hubspotIngest: List[IO[Unit]] =
                if (cfg.hubspot.enabled)
                  List(
                    Supervised(
                      "ingest-hubspot",
                      ingestScheduler.loop(
                        new HubSpotConnector[IO](new HttpHubSpotApi[IO](http, cfg.hubspot.token, cfg.hubspot.baseUrl)),
                        List("companies", "contacts", "deals"),
                        15.minutes
                      )
                    )
                  )
                else Nil
              // MRPeasy: the inventory/serial authority. Recent-window sync of orders + shipments (serials → genealogy).
              val mrpeasyIngest: List[IO[Unit]] =
                if (cfg.mrpeasy.enabled)
                  List(
                    Supervised(
                      "ingest-mrpeasy",
                      ingestScheduler.loop(
                        new MrpeasyConnector[IO](
                          new HttpMrpeasyApi[IO](http, cfg.mrpeasy.accessKey, cfg.mrpeasy.apiKey, cfg.mrpeasy.baseUrl)
                        ),
                        List("customer_orders", "shipments"),
                        15.minutes
                      )
                    )
                  )
                else Nil
              logger.info(
                if (cfg.hubspot.enabled) "S2: HubSpot live ingest ON (companies, contacts → inbox every 15m)"
                else "S2: HubSpot live ingest DORMANT (no HUBSPOT_TOKEN — set it to light up)"
              ) *>
                logger.info(
                  if (cfg.mrpeasy.enabled)
                    "S2: MRPeasy live ingest ON (customer_orders, shipments + serials → inbox every 15m)"
                  else "S2: MRPeasy live ingest DORMANT (no MRPEASY keys — set them to light up)"
                ) *>
                logger.info(
                  "Consumer running: outbox relay + inbound inbox (relay+mapping) + Xero invoice + revenue recognition + Stripe settlement + document generation + VAT remittance + PII tombstone"
                ) *>
                List(
                  Supervised("outbox-relay", relayLoop),
                  Supervised("inbound-relay", inboundRelayLoop),
                  Supervised("inbound-mapping", inboundConsumer.runForever),
                  Supervised("xero-invoice", xeroConsumer.runForever),
                  Supervised("revenue-recognition", revConsumer.runForever),
                  Supervised("stripe-settlement", stripeLoop),
                  Supervised("document-generation", docConsumer.runForever),
                  Supervised("invoice-void", voidConsumer.runForever),
                  Supervised("vat-remittance", vatRemitConsumer.runForever),
                  Supervised("pii-tombstone", piiTombstoneConsumer.runForever),
                  Supervised("rebate-accrual", rebateAccrualConsumer.runForever),
                  Supervised("placement", placementConsumer.runForever),
                  Supervised("return-effector", returnConsumer.runForever),
                  Supervised("opening-inventory", openingInvConsumer.runForever),
                  Supervised("commission-accrual", commissionConsumer.runForever),
                  Supervised("order-commitment", commitmentConsumer.runForever),
                  Supervised("shadow-validation", shadowLoop),
                  Supervised("notification-delivery", notifyLoop),
                  Supervised("forecast-cycle", forecastLoop),
                  Supervised("h6q-cycle", h6qCycleLoop)
                ).++(hubspotIngest).++(mrpeasyIngest).parSequence_
          }
        }
    }
}
