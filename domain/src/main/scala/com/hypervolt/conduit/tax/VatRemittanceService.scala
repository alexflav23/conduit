package com.hypervolt.conduit.tax

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.LedgerTransfer
import com.hypervolt.conduit.ledger.LedgerTransferCode
import com.hypervolt.conduit.ledger.Ledgers
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

  def vatAcc(entity: UUID): BigInt = TbIds.accountId(s"VAT:$entity")
  def bank(entity: UUID): BigInt   = TbIds.accountId(s"BANK:$entity")

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
    Currency.fromCode(currency) match {
      case None => s"unknown currency $currency".asLeft[UUID].pure[F]
      case Some(ccy) =>
        val id       = UUID.randomUUID()
        val ledgerId = Ledgers.forCurrency(ccy)
        val accounts = List(
          LedgerAccount(vatAcc(entity), ledgerId, LedgerAccountCode.Vat),
          LedgerAccount(bank(entity), ledgerId, LedgerAccountCode.Bank)
        )
        // DR VAT (reduce the liability) / CR BANK (cash out) — debit_account, credit_account.
        val transfer =
          LedgerTransfer(
            TbIds.transferId(id, 0),
            vatAcc(entity),
            bank(entity),
            minor(amount),
            ledgerId,
            LedgerTransferCode.Payment
          )
        ledger.createAccounts(accounts) *>
          ledger.postTransfers(List(transfer).filter(_.amount > 0)) *>
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
             ${TbIds.transferId(id, 0).bigInteger.toString}::numeric, $actor)""".update.run.flatMap(n =>
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

  // The immutable-proof tie: the per-jurisdiction projection summed for the entity must equal the ledger VAT balance.
  def reconcile(entity: UUID): F[Json] =
    (VatExposureRepo.outstandingForEntity(entity).transact(xa), ledger.balance(vatAcc(entity))).mapN {
      (projected, bal) =>
        val ledgerOutstanding = (BigDecimal(bal.creditsPosted) - BigDecimal(bal.debitsPosted)) / 100
        Json.obj(
          "entity_id"             -> entity.toString.asJson,
          "projected_outstanding" -> projected.setScale(2, RoundingMode.HALF_UP).asJson,
          "ledger_vat_balance"    -> ledgerOutstanding.setScale(2, RoundingMode.HALF_UP).asJson,
          "matched" -> (projected.setScale(2, RoundingMode.HALF_UP) == ledgerOutstanding
            .setScale(2, RoundingMode.HALF_UP)).asJson
        )
    }
}
