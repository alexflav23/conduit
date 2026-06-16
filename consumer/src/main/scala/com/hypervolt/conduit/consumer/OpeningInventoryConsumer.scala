package com.hypervolt.conduit.consumer

import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.migration.MigrationService
import com.hypervolt.conduit.migration.OpeningLine
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.apache.pulsar.client.api.Consumer
import org.apache.pulsar.client.api.PulsarClient
import org.apache.pulsar.client.api.SubscriptionInitialPosition
import org.apache.pulsar.client.api.SubscriptionType
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scala.math.BigDecimal.RoundingMode

// Posts the opening inventory balance — DR INV / CR OPENING_BALANCE_EQUITY at the total landed value of the costed
// lots — the counterpart the recognition COGS relief credits against, so INV nets to the on-hand value and the
// inventory↔count reconciliation TIES (rather than being signed off). Triggered by the boot ignition's
// inventory.opening event (so lots exist first); idempotent — deterministic transfer id ⇒ TB no-op on re-post.
final class OpeningInventoryConsumer[F[_]: Async](client: PulsarClient, xa: Transactor[F], migration: MigrationService[F]) {

  private val logger       = Slf4jLogger.getLogger[F]
  private val topic        = "conduit.inventory"
  private val subscription = "conduit-opening-inventory-1"

  private def subscribe: Resource[F, Consumer[EventEnvelope]] =
    Resource.fromAutoCloseable(
      Async[F].blocking(
        client
          .newConsumer(AvroPulsarSchema.avroSchema[EventEnvelope])
          .topic(topic)
          .subscriptionName(subscription)
          .subscriptionType(SubscriptionType.Shared)
          .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
          .subscribe()
      )
    )

  def runForever: F[Unit] =
    subscribe.use(c => logger.info(s"Opening-inventory consumer subscribed to $topic") *> loop(c).foreverM)

  private def loop(c: Consumer[EventEnvelope]): F[Unit] =
    Async[F].fromCompletableFuture(Async[F].delay(c.receiveAsync())).flatMap { msg =>
      handle(msg.getValue)
        .flatMap(_ => Async[F].blocking(c.acknowledge(msg)).void)
        .handleErrorWith(t =>
          logger.error(t)("opening-inventory failed; nacking") *> Async[F].blocking(c.negativeAcknowledge(msg)).void
        )
    }

  // Opening INV must net (against the per-dispatch COGS relief) to the on-hand value to the penny, so it is computed
  // on the SAME basis recognition relieves it: Σ per-dispatch round(serials' landed cost) + the on-hand value.
  // (A single round(grand total) would drift by the accumulated per-dispatch rounding.)
  private def totalLotValueMinor: doobie.ConnectionIO[BigInt] =
    sql"""SELECT (
            COALESCE((SELECT SUM(round(c.cogs, 2)) FROM (
              SELECT s.dispatch_id, SUM(lb.landed_unit_cost) AS cogs
              FROM serial_unit s JOIN lot_batch lb ON lb.id = s.lot_batch_id
              WHERE s.dispatch_id IS NOT NULL GROUP BY s.dispatch_id) c), 0)
            + COALESCE((SELECT round(SUM(lb.landed_unit_cost), 2)
              FROM serial_unit s JOIN lot_batch lb ON lb.id = s.lot_batch_id WHERE s.dispatch_id IS NULL), 0)
          )"""
      .query[BigDecimal]
      .unique
      .map(v => (v.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt)

  private def handle(env: EventEnvelope): F[Unit] =
    if (env.event_type != "inventory.opening") Async[F].unit
    else
      parse(new String(env.payload, StandardCharsets.UTF_8)).toOption
        .flatMap(_.hcursor.get[String]("entity_id").toOption)
        .flatMap(s => scala.util.Try(UUID.fromString(s)).toOption) match {
        case None => Async[F].unit
        case Some(entity) =>
          totalLotValueMinor.transact(xa).flatMap { minor =>
            if (minor <= BigInt(0)) Async[F].unit
            else
              migration.ensureAccounts(entity, Currency.GBP, List((migration.invAccount(entity), LedgerAccountCode.Inv))) *>
                migration.postOpeningBalances(
                  "ignition",
                  "opening_inventory",
                  entity,
                  Currency.GBP,
                  List(OpeningLine(entity.toString, s"INV:$entity", LedgerAccountCode.Inv, debitNormal = true, minorAmount = minor))
                )
          }
      }
}
