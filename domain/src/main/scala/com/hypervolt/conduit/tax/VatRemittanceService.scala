package com.hypervolt.conduit.tax

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.ledger.Journal
import com.hypervolt.conduit.ledger.JournalAccount
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.LedgerTransferCode
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.Posting
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// VAT remittance (doc 16 §1.3): paying a tax authority depletes the accrued exposure on the immutable ledger —
// DR VAT:<entity> / CR BANK:<entity>, the transfer id deterministic from the remittance id (redelivery is a no-op).
// `reconcile` proves the projection ties to the ledger: Σ outstanding (recognised − reversed − remitted) for an
// entity == its VAT:<entity> credit balance. (Posts TigerBeetle, so it runs outside the API — consumer/service.)
final class VatRemittanceService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  def vatAcc(entity: UUID, jurisdiction: String): BigInt = TbIds.accountId(s"VAT:$entity:$jurisdiction")
  def bank(entity: UUID): BigInt                         = TbIds.accountId(s"BANK:$entity")

  private val journal = new Journal[F](xa, ledger)

  private def minor(amount: BigDecimal): BigInt = (amount.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt

  def remit(
      entity: UUID,
      jurisdiction: String,
      periodKey: String,
      amount: BigDecimal,
      currency: String,
      reference: Option[String],
      actor: String
  ): F[Either[String, UUID]] =
    remitWithId(UUID.randomUUID(), entity, jurisdiction, periodKey, amount, currency, reference, actor)

  // Idempotent on the remittance id: the consumer derives it from the request event id, so an at-least-once
  // redelivery is a no-op (ON CONFLICT DO NOTHING + deterministic TB transfer id).
  def remitWithId(
      id: UUID,
      entity: UUID,
      jurisdiction: String,
      periodKey: String,
      amount: BigDecimal,
      currency: String,
      reference: Option[String],
      actor: String
  ): F[Either[String, UUID]] =
    Currency.fromCode(currency) match {
      case None => s"unknown currency $currency".asLeft[UUID].pure[F]
      case Some(ccy) =>
        val ledgerId = Ledgers.forCurrency(ccy)
        val accounts = List(
          LedgerAccount(vatAcc(entity, jurisdiction), ledgerId, LedgerAccountCode.Vat),
          LedgerAccount(bank(entity), ledgerId, LedgerAccountCode.Bank)
        )
        // DR VAT (reduce the liability) / CR BANK (cash out).
        val posting = Posting(
          id,
          0,
          JournalAccount(s"VAT:$entity:$jurisdiction", LedgerAccountCode.Vat, Some(entity)),
          JournalAccount(s"BANK:$entity", LedgerAccountCode.Bank, Some(entity)),
          ccy,
          minor(amount),
          transferCode = LedgerTransferCode.Payment
        )
        ledger.createAccounts(accounts) *>
          journal.postOne(Instant.now(), posting) *>
          record(id, entity, jurisdiction, periodKey, amount, currency, reference, actor)
            .transact(xa)
            .as(id.asRight[String])
    }

  private def record(
      id: UUID,
      entity: UUID,
      jurisdiction: String,
      periodKey: String,
      amount: BigDecimal,
      currency: String,
      reference: Option[String],
      actor: String
  ): ConnectionIO[Int] =
    sql"""INSERT INTO vat_remittance (id, entity_id, jurisdiction, period_key, amount, currency, reference, tb_transfer_id, actor)
          VALUES ($id, $entity, $jurisdiction, $periodKey, $amount, $currency, $reference,
             ${TbIds.transferId(id, 0).bigInteger.toString}::numeric, $actor)
          ON CONFLICT (id) DO NOTHING""".update.run.flatMap(n =>
      OutboxRepo
        .append(
          OutboxEvent(
            UUID.randomUUID(),
            "tax.vat.remitted",
            1,
            "tax",
            id,
            s"$entity:$jurisdiction",
            None,
            None,
            None,
            Json.obj(
              "vat_remittance_id" -> id.toString.asJson,
              "entity_id"         -> entity.toString.asJson,
              "jurisdiction"      -> jurisdiction.asJson,
              "period_key"        -> periodKey.asJson,
              "amount"            -> amount.asJson,
              "currency"          -> currency.asJson
            ),
            Instant.now(),
            "service:tax"
          )
        )
        .as(n)
    )

  // The immutable-proof tie, per (entity, jurisdiction): the projection's outstanding must equal the
  // VAT:<entity>:<jurisdiction> ledger balance (credits − debits).
  def reconcile(entity: UUID, jurisdiction: String): F[Json] =
    (VatExposureRepo.outstandingFor(entity, jurisdiction).transact(xa), ledger.balance(vatAcc(entity, jurisdiction)))
      .mapN { (projected, bal) =>
        val ledgerOutstanding = (BigDecimal(bal.creditsPosted) - BigDecimal(bal.debitsPosted)) / 100
        Json.obj(
          "entity_id"             -> entity.toString.asJson,
          "jurisdiction"          -> jurisdiction.asJson,
          "projected_outstanding" -> projected.setScale(2, RoundingMode.HALF_UP).asJson,
          "ledger_vat_balance"    -> ledgerOutstanding.setScale(2, RoundingMode.HALF_UP).asJson,
          "matched" -> (projected.setScale(2, RoundingMode.HALF_UP) == ledgerOutstanding
            .setScale(2, RoundingMode.HALF_UP)).asJson
        )
      }
}
