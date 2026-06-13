package com.hypervolt.conduit.intercompany

import cats.effect.Async
import cats.syntax.all._
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
import com.hypervolt.conduit.money.Money
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

final case class TrueUpResult(id: UUID, prior: BigDecimal, target: BigDecimal, adjustment: BigDecimal)

private final case class TuHead(
    pr: UUID,
    op: UUID,
    ccy: String,
    from: LocalDate,
    to: LocalDate,
    prior: BigDecimal,
    target: BigDecimal,
    adjustment: BigDecimal,
    status: String,
    proposedBy: UUID
)

// §482 / OECD year-end transfer-pricing true-up (spec doc 28 §5.6), maker <> checker. The proposer states
// the arm's-length AGGREGATE uplift the period should show; approval posts ONE matched pair adjusting the
// period's intercompany margin to it — the same sign-aware IC_AP/IC_AR/IC_MARGIN shape as the flash hop, at
// period grain, eliminated at group. The adjustment is allocated conservingly across the period's matches
// (the L1 largest-remainder allocator) for TP documentation — ic_match is NEVER rewritten (L6). This is
// §482 compliance, NOT the ASC-606 customer rebate (doc 24): similar machinery, a different standard.
final class IcTrueUpService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val journal = new Journal[F](xa, ledger)

  private def minor(a: BigDecimal): BigInt = (a.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt

  // The matches in the window — their uplift is the prior, and their weights drive the documentation split.
  private def periodMatches(
      pr: UUID,
      op: UUID,
      ccy: String,
      from: LocalDate,
      to: LocalDate
  ): ConnectionIO[List[(UUID, BigDecimal)]] =
    sql"""SELECT dispatch_id, (uplift_total - returned_uplift)
          FROM ic_match
          WHERE procurement_entity_id = $pr AND operating_entity_id = $op AND currency = $ccy
            AND reversed_at IS NULL AND created_at::date >= $from AND created_at::date <= $to
          ORDER BY dispatch_id"""
      .query[(UUID, BigDecimal)]
      .to[List]

  def propose(
      pr: UUID,
      op: UUID,
      ccy: String,
      from: LocalDate,
      to: LocalDate,
      targetUplift: BigDecimal,
      proposer: UUID
  ): F[Either[String, UUID]] =
    periodMatches(pr, op, ccy, from, to)
      .flatMap { ms =>
        if (ms.isEmpty) "no intercompany matches in the period — nothing to true up".asLeft[UUID].pure[ConnectionIO]
        else {
          val prior = ms.map(_._2).sum
          sql"""INSERT INTO ic_true_up
                (procurement_entity_id, operating_entity_id, txn_currency, period_from, period_to,
                 prior_uplift, target_uplift, adjustment, proposed_by)
              VALUES ($pr, $op, $ccy, $from, $to, $prior, $targetUplift, ${targetUplift - prior}, $proposer)
              RETURNING id""".query[UUID].unique.map(_.asRight[String])
        }
      }
      .transact(xa)

  def approve(trueUpId: UUID, approver: UUID): F[Either[String, TrueUpResult]] =
    head(trueUpId).transact(xa).flatMap {
      case None                              => "no such true-up".asLeft[TrueUpResult].pure[F]
      case Some(h) if h.status != "proposed" => s"true-up is ${h.status}, not proposed".asLeft[TrueUpResult].pure[F]
      case Some(h) if h.proposedBy == approver =>
        "the proposer cannot approve their own true-up (segregation of duties)".asLeft[TrueUpResult].pure[F]
      case Some(h) => post(trueUpId, approver, h)
    }

  private def head(id: UUID): ConnectionIO[Option[TuHead]] =
    sql"""SELECT procurement_entity_id, operating_entity_id, txn_currency, period_from, period_to,
                 prior_uplift, target_uplift, adjustment, status, proposed_by
          FROM ic_true_up WHERE id = $id"""
      .query[TuHead]
      .option

  private def post(id: UUID, approver: UUID, h: TuHead): F[Either[String, TrueUpResult]] =
    Currency.fromCode(h.ccy) match {
      case None => s"unknown currency ${h.ccy}".asLeft[TrueUpResult].pure[F]
      case Some(ccy) =>
        val ledgerId = Ledgers.forCurrency(ccy)
        val e        = Some(h.op)
        val pe       = Some(h.pr)
        val icAp     = JournalAccount(s"IC_AP:${h.op}:${h.pr}", LedgerAccountCode.Intercompany, e)
        val icAr     = JournalAccount(s"IC_AR:${h.pr}:${h.op}", LedgerAccountCode.Intercompany, pe)
        val margin   = JournalAccount(s"IC_MARGIN:${h.pr}", LedgerAccountCode.IcMargin, pe)
        val cogs     = JournalAccount(s"COGS:${h.op}", LedgerAccountCode.CosClearing, e)
        val amt      = minor(h.adjustment.abs)
        // adjustment > 0: arm's-length says MORE margin to the principal — op's cost rises (DR COGS / CR
        // IC_AP), principal's receivable + margin rise (DR IC_AR / CR IC_MARGIN). Below-zero flips the pair.
        val opPair = if (h.adjustment >= 0) (cogs, icAp) else (icAp, cogs)
        val prPair = if (h.adjustment >= 0) (icAr, margin) else (margin, icAr)
        val accounts = List(
          LedgerAccount(TbIds.accountId(icAp.key), ledgerId, LedgerAccountCode.Intercompany),
          LedgerAccount(TbIds.accountId(icAr.key), ledgerId, LedgerAccountCode.Intercompany),
          LedgerAccount(TbIds.accountId(margin.key), ledgerId, LedgerAccountCode.IcMargin),
          LedgerAccount(TbIds.accountId(cogs.key), ledgerId, LedgerAccountCode.CosClearing)
        )
        val posted = minor(h.adjustment.abs) > 0
        val opLeg  = Option.when(posted)(BigDecimal(TbIds.transferId(id, 0)))
        val prLeg  = Option.when(posted)(BigDecimal(TbIds.transferId(id, 1)))
        val journalIO =
          if (!posted) Async[F].unit
          else
            ledger.createAccounts(accounts) *>
              journal.post(
                Instant.now(),
                List(
                  Posting(id, 0, opPair._1, opPair._2, ccy, amt, transferCode = LedgerTransferCode.TrueUp),
                  Posting(id, 1, prPair._1, prPair._2, ccy, amt, transferCode = LedgerTransferCode.TrueUp)
                )
              )
        journalIO *> record(id, approver, h, opLeg, prLeg)
          .transact(xa)
          .as(
            TrueUpResult(id, h.prior, h.target, h.adjustment).asRight[String]
          )
    }

  private def record(
      id: UUID,
      approver: UUID,
      h: TuHead,
      opLeg: Option[BigDecimal],
      prLeg: Option[BigDecimal]
  ): ConnectionIO[Unit] =
    for {
      ms <- periodMatches(h.pr, h.op, h.ccy, h.from, h.to)
      // allocate the adjustment conservingly across the period's matches for TP documentation (Σ == adjustment)
      _ <- allocationLines(id, h.adjustment, ms, Currency.fromCode(h.ccy).get)
      _ <- sql"""UPDATE ic_true_up SET status = 'approved', approved_by = $approver, approved_at = now(),
                   op_leg_tb_transfer_id = $opLeg, pr_leg_tb_transfer_id = $prLeg WHERE id = $id""".update.run
    } yield ()

  private def allocationLines(
      id: UUID,
      adjustment: BigDecimal,
      ms: List[(UUID, BigDecimal)],
      ccy: Currency
  ): ConnectionIO[Unit] =
    if (ms.isEmpty || minor(adjustment.abs) == 0) ().pure[ConnectionIO]
    else {
      // weights are the matches' uplift magnitudes; allocate |adjustment| then carry the adjustment's sign
      val weights = ms.map(m => m._2.abs)
      val parts =
        Money.allocate(Money.of(adjustment.abs, ccy), weights.toVector).map(_.amount)
      val sign = if (adjustment < 0) BigDecimal(-1) else BigDecimal(1)
      ms.zip(parts).traverse_ {
        case ((dispatchId, _), part) =>
          sql"""INSERT INTO ic_true_up_line (true_up_id, dispatch_id, allocated)
                VALUES ($id, $dispatchId, ${part * sign})""".update.run
      }
    }
}
