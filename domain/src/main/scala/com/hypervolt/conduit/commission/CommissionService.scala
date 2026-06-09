package com.hypervolt.conduit.commission

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.ledger._
import com.hypervolt.conduit.money.Currency
import doobie.implicits._
import doobie.util.transactor.Transactor
import java.time.Instant
import java.util.UUID

// Commission lifecycle via TigerBeetle two-phase (doc 04 §Commission): accrue -> PENDING transfer to
// COMM_PAYABLE:<agent>; posted -> post_pending (earned); clawed -> void_pending. GBP minor units for now
// (the gross-margin true-up to actual batch cost lands with M7 batches).
final class CommissionService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F], expenseEntity: String) {

  private val journal = new Journal[F](xa, ledger)

  def expenseAccount(currency: String): BigInt                = TbIds.accountId(s"COMM_EXPENSE:$expenseEntity:$currency")
  def payableAccount(agentId: UUID, currency: String): BigInt = TbIds.accountId(s"COMM_PAYABLE:$agentId:$currency")

  private def expenseLeg(currency: String): JournalAccount =
    JournalAccount(s"COMM_EXPENSE:$expenseEntity:$currency", LedgerAccountCode.CommissionExpense, None)
  private def payableLeg(agentId: UUID, currency: String): JournalAccount =
    JournalAccount(s"COMM_PAYABLE:$agentId:$currency", LedgerAccountCode.CommPayable, None)

  private def minor(amount: BigDecimal): BigInt = (amount * 100).toBigInt

  // Accrue one line's commission: PENDING transfer + a `pending` commission_entry. Returns the entry id.
  def accrue(
      agentId: UUID,
      schemeId: UUID,
      orderId: Option[UUID],
      currency: String,
      scheme: CommissionScheme,
      line: CommissionLineInput
  ): F[UUID] = {
    val (basis, amount) = CommissionResolver.lineCommission(scheme, line)
    val entryId         = UUID.randomUUID()
    val transferId      = TbIds.transferId(entryId, 0)
    val posting = Posting(
      entryId,
      0,
      expenseLeg(currency),
      payableLeg(agentId, currency),
      Currency.GBP,
      minor(amount),
      transferCode = LedgerTransferCode.Commission,
      phase = JournalPhase.Pending
    )
    journal.postOne(Instant.now(), posting) *>
      CommissionRepo
        .insertEntry(
          entryId,
          agentId,
          schemeId,
          orderId,
          basis,
          scheme.ratePct,
          amount,
          currency,
          "pending",
          transferId.toString
        )
        .transact(xa)
        .as(entryId)
  }

  def post(entryId: UUID, amount: BigDecimal): F[Unit] =
    journal.postPending(Instant.now(), entryId, 1, (entryId, 0), minor(amount)) *>
      CommissionRepo.setStatus(entryId, "posted").transact(xa).void

  def claw(entryId: UUID): F[Unit] =
    journal.voidPending(Instant.now(), entryId, 2, (entryId, 0)) *>
      CommissionRepo.setStatus(entryId, "clawed").transact(xa).void

  // Statement reconciliation: posted commission entries for an agent must equal the COMM_PAYABLE posted credits.
  def statementTotal(agentId: UUID): F[BigDecimal] = CommissionRepo.postedTotal(agentId).transact(xa)

  // True-up (doc 04 §Commission, closes M5-R): recompute the line's commission on the ACTUAL batch landed
  // cost (vs the provisional std_cost used at accrual) and book the delta as a current-period adjustment.
  // Posted entries are never reopened — the delta is a new `true_up_adjustment` entry + ledger movement.
  def trueUp(
      agentId: UUID,
      schemeId: UUID,
      orderId: Option[UUID],
      currency: String,
      rate: BigDecimal,
      unitPriceExVat: BigDecimal,
      qty: Int,
      originalAmount: BigDecimal,
      actualUnitCost: BigDecimal
  ): F[(UUID, BigDecimal)] = {
    val actualBasis  = (unitPriceExVat - actualUnitCost) * BigDecimal(qty)
    val actualAmount = (actualBasis * rate / 100).setScale(2, scala.math.BigDecimal.RoundingMode.HALF_UP)
    val delta        = actualAmount - originalAmount
    val entryId      = UUID.randomUUID()
    val transferId   = TbIds.transferId(entryId, 0)
    val absMinor     = (delta.abs * 100).toBigInt
    // delta>=0: more owed (DR expense, CR payable); delta<0: claw back (DR payable, CR expense). A zero delta posts
    // nothing (the Journal skips a zero-amount single leg) but still records the true_up_adjustment entry.
    val (debit, credit) =
      if (delta >= 0) (expenseLeg(currency), payableLeg(agentId, currency))
      else (payableLeg(agentId, currency), expenseLeg(currency))
    journal.postOne(
      Instant.now(),
      Posting(entryId, 0, debit, credit, Currency.GBP, absMinor, transferCode = LedgerTransferCode.Commission)
    ) *>
      CommissionRepo
        .insertEntry(
          entryId,
          agentId,
          schemeId,
          orderId,
          actualBasis.setScale(2, scala.math.BigDecimal.RoundingMode.HALF_UP),
          rate,
          delta,
          currency,
          "posted",
          transferId.toString,
          kind = "true_up_adjustment"
        )
        .transact(xa)
        .as((entryId, delta))
  }
}
