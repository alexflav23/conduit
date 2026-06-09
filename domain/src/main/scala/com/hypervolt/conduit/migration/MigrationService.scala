package com.hypervolt.conduit.migration

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.ledger.Journal
import com.hypervolt.conduit.ledger.JournalAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.LedgerTransferCode
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.Posting
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.money.Money
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// Outcome of a backfill step. `migrated=false` means the row was already present — a no-op re-run (doc 18 §3.1).
final case class BackfillResult(conduitId: UUID, eventId: UUID, migrated: Boolean)

// An opening balance to post against OPENING_BALANCE_EQUITY (doc 18 §2). `account` is the asset/liability side;
// `debitNormal` says whether it is debit-normal (asset, e.g. INV/AR) or credit-normal (liability, e.g. AP).
final case class OpeningLine(
    sourceId: String,
    accountKey: String,
    accountRole: Int,
    debitNormal: Boolean,
    minorAmount: BigInt
)

// The migration/cutover subsystem (doc 18). This is NOT a bespoke importer — it is a backfill *emitter* that
// produces the same domain events the live API would (pushed through the outbox -> Pulsar -> the normal
// consumers), so projections, genealogy, the warranty register and the ledger are rebuilt by exactly the
// production code (doc 18 §3). Opening balances post as audited TigerBeetle transfers against
// OPENING_BALANCE_EQUITY so the books balance by construction. Every identity is deterministic, so re-running
// is a no-op at three independent layers (migration_record dedupe, event_id dedupe, transfer-exists).
final class MigrationService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val journal = new Journal[F](xa, ledger)

  def openingEquity(entity: UUID): BigInt = TbIds.accountId(s"OPENING_BALANCE_EQUITY:$entity")
  def invAccount(entity: UUID): BigInt    = TbIds.accountId(s"INV:$entity")
  def arAccount(party: UUID): BigInt      = TbIds.accountId(s"AR:$party")
  def apAccount(supplier: UUID): BigInt   = TbIds.accountId(s"AP:$supplier")

  def minor[C <: Currency](m: Money[C]): BigInt =
    (m.amount.setScale(m.currency.minorUnits, RoundingMode.HALF_UP) * BigDecimal(
      BigInt(10).pow(m.currency.minorUnits)
    )).toBigInt

  // ----- idempotent backfill of one source row (doc 18 §3.1) -----

  // Dedupe on (source, entity_type, source_id); on a new row, run the business-row write, append the outbox
  // event and write the provenance record — ALL in one Postgres transaction. The event carries a deterministic
  // event_id so the downstream consumer dedupes a redelivery (idempotency layer 2). `persist` receives the
  // deterministic conduitId so the same source row never forks identity (layer 3).
  def backfill(
      source: String,
      entityType: String,
      sourceId: String,
      payload: Json,
      phase: Int,
      batchId: UUID,
      eventType: String,
      aggregateType: String,
      caveats: List[String] = Nil
  )(persist: UUID => ConnectionIO[Unit]): F[BackfillResult] = {
    val conduitId = MigIds.conduitId(source, entityType, sourceId)
    val eventId   = MigIds.eventId(source, entityType, sourceId)
    MigrationRepo
      .existing(source, entityType, sourceId)
      .flatMap {
        case Some(existing) => BackfillResult(existing, eventId, migrated = false).pure[ConnectionIO]
        case None =>
          val event = OutboxEvent(
            eventId,
            eventType,
            1,
            aggregateType,
            conduitId,
            conduitId.toString,
            None,
            Some(batchId),
            Some(batchId),
            payload.deepMerge(
              Json.obj("_migration" -> Json.obj("source" -> source.asJson, "source_id" -> sourceId.asJson))
            ),
            Instant.now()
          )
          persist(conduitId) *>
            OutboxRepo.append(event) *>
            MigrationRepo
              .record(source, entityType, sourceId, conduitId, batchId, payload, Some(eventId), phase, caveats)
              .as(BackfillResult(conduitId, eventId, migrated = true))
      }
      .transact(xa)
  }

  // ----- opening balances into TigerBeetle (doc 18 §2) -----

  // Post each opening line against OPENING_BALANCE_EQUITY on the currency's ledger. Asset openings DR the asset
  // / CR equity; liability openings DR equity / CR the liability. Transfer ids are deterministic from the source
  // row, so a re-run returns `exists` and posts nothing (idempotency layer 2). After posting, each row is stamped
  // back to migration_record (re-performable: ledger -> transfer -> source row).
  def postOpeningBalances(
      source: String,
      entityType: String,
      entity: UUID,
      currency: Currency,
      lines: List[OpeningLine]
  ): F[Unit] = {
    val equityLeg = JournalAccount(s"OPENING_BALANCE_EQUITY:$entity", LedgerAccountCode.OpeningEquity, Some(entity))
    val postings = lines.map { l =>
      val acct     = JournalAccount(l.accountKey, l.accountRole, Some(entity))
      val (dr, cr) = if (l.debitNormal) (acct, equityLeg) else (equityLeg, acct)
      Posting(
        MigIds.eventId(source, entityType, l.sourceId),
        0,
        dr,
        cr,
        currency,
        l.minorAmount,
        transferCode = LedgerTransferCode.Opening,
        id = Some(MigIds.transferId(source, entityType, l.sourceId, 0))
      )
    }
    journal.post(Instant.now(), postings) *>
      lines.traverse_(l =>
        MigrationRepo
          .setTransfer(source, entityType, l.sourceId, MigIds.transferId(source, entityType, l.sourceId, 0))
          .transact(xa)
          .void
      )
  }

  // Ensure the asset/liability/equity accounts exist on the currency ledger before opening transfers land.
  def ensureAccounts(entity: UUID, currency: Currency, assetAccounts: List[(BigInt, Int)]): F[Unit] = {
    val ledgerId = Ledgers.forCurrency(currency)
    val accounts = (openingEquity(entity), LedgerAccountCode.OpeningEquity) :: assetAccounts
    ledger.createAccounts(accounts.map {
      case (id, code) => com.hypervolt.conduit.ledger.LedgerAccount(id, ledgerId, code)
    })
  }

  // ----- reconciliation (doc 18 §4–5) -----

  // The opening trial balance: Σ(debitsPosted - creditsPosted) over EVERY account the opening transfers touched
  // must be zero (doc 18 §2 "Σ debits == Σ credits == 0 net against OPENING_BALANCE_EQUITY"). A non-zero residual
  // blocks cutover (gate G4).
  def openingTrialBalanceResidual(accountIds: List[BigInt]): F[BigInt] =
    accountIds.traverse(ledger.balance).map(_.foldLeft(BigInt(0))((acc, b) => acc + (b.debitsPosted - b.creditsPosted)))

  // A single reconciliation comparison written to the `reconciliation` register (doc 02 §N) so it surfaces in
  // the Auditability Center (doc 14 §6). Zero tolerance on money/units — there is no "close enough" (doc 18 §4.2).
  def reconcile(
      reconType: String,
      periodId: UUID,
      scope: Json,
      currency: Option[String],
      expected: BigDecimal,
      actual: BigDecimal
  ): F[String] = {
    val variance = actual - expected
    val status   = if (variance.signum == 0) "matched" else "exception"
    sql"""INSERT INTO reconciliation (type, period_id, scope, expected, actual, currency, variance, status)
          VALUES ($reconType, $periodId, $scope, $expected, $actual, $currency, $variance, $status)""".update.run
      .transact(xa)
      .as(status)
  }

  // Cutover stock validation (doc 18 §5): units must tie exactly and the INV ledger value must tie to the count
  // to the penny. Returns ("matched"/"exception") per (units, value).
  def cutoverStockValidation(
      entity: UUID,
      periodId: UUID,
      currency: Currency,
      countedUnits: Int,
      systemUnits: Int,
      countedValueMinor: BigInt
  ): F[(String, String)] =
    reconcile(
      "inventory_vs_count",
      periodId,
      Json.obj("entity" -> entity.toString.asJson, "dimension" -> "units".asJson),
      None,
      BigDecimal(countedUnits),
      BigDecimal(systemUnits)
    ).flatMap { unitsStatus =>
      ledger.balance(invAccount(entity)).flatMap { inv =>
        val ledgerMinor = inv.debitsPosted - inv.creditsPosted
        reconcile(
          "tb_vs_gl",
          periodId,
          Json.obj("entity" -> entity.toString.asJson, "account" -> "INV".asJson),
          Some(currency.code),
          BigDecimal(countedValueMinor) / BigDecimal(BigInt(10).pow(currency.minorUnits)),
          BigDecimal(ledgerMinor) / BigDecimal(BigInt(10).pow(currency.minorUnits))
        ).map(valueStatus => (unitsStatus, valueStatus))
      }
    }
}
