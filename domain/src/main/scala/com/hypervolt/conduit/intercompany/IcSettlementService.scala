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
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

private final case class SettleHead(
    pr: UUID,
    op: UUID,
    txnCcy: String,
    fnCcy: String,
    asOf: LocalDate,
    status: String,
    proposedBy: UUID
)

private final case class SettleFigures(
    netTxn: BigDecimal,
    hedgedTxn: BigDecimal,
    bookedFn: BigDecimal,
    priorDeltas: BigDecimal,
    settledRate: Option[BigDecimal],
    rateSource: String,
    settledFn: BigDecimal,
    realizedFx: BigDecimal
)

final case class SettlementResult(
    id: UUID,
    netTxn: BigDecimal,
    hedgedTxn: BigDecimal,
    bookedFunctional: BigDecimal,
    settledFunctional: BigDecimal,
    realizedFx: BigDecimal
)

// IC settlement (spec doc 28 §5.4), maker <> checker. Approval settles the FULL open set of the pair:
//   leg 0/1 — cash in the TRANSACTION currency on both books (DR IC_AP / CR BANK; DR BANK / CR IC_AR),
//             sign-aware for a net-negative (below-cost-dominated) balance;
//   leg 2   — the FINAL remeasure-to-settlement delta on the unhedged portion, so cumulative unrealized FX
//             equals exactly the realized total;
//   leg 3   — the RECLASS: the remeasurement adjunct clears into FX_SETTLED — unrealized becomes realized
//             exactly once, never twice (FX_GAINLOSS saw every penny through the deltas, and only there).
// Hedge-booked exposure settles at its contracted rate — zero FX, the lock proven in cash — and releases its
// live drawdown (notional stays consumed: the forward executed). Covered matches are stamped append-only;
// every open-exposure definition (remeasurement, hedge lock) already excludes them.
final class IcSettlementService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val journal = new Journal[F](xa, ledger)

  private def minor(a: BigDecimal): BigInt = (a.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt

  def propose(pr: UUID, op: UUID, txnCcy: String, asOf: LocalDate, proposer: UUID): F[Either[String, UUID]] =
    (for {
      fnCcy <- sql"SELECT functional_currency FROM entity WHERE id = $pr".query[String].unique
      id    <- sql"""INSERT INTO ic_settlement
                    (procurement_entity_id, operating_entity_id, txn_currency, functional_currency, as_of, proposed_by)
                  VALUES ($pr, $op, $txnCcy, $fnCcy, $asOf, $proposer) RETURNING id""".query[UUID].unique
    } yield id.asRight[String]).transact(xa)

  def approve(settlementId: UUID, approver: UUID): F[Either[String, SettlementResult]] =
    head(settlementId).transact(xa).flatMap {
      case None => "no such settlement".asLeft[SettlementResult].pure[F]
      case Some(h) if h.status != "proposed" =>
        s"settlement is ${h.status}, not proposed".asLeft[SettlementResult].pure[F]
      case Some(h) if h.proposedBy == approver =>
        "the proposer cannot approve their own settlement (segregation of duties)".asLeft[SettlementResult].pure[F]
      case Some(h) =>
        figures(h).transact(xa).flatMap {
          case Left(e)  => e.asLeft[SettlementResult].pure[F]
          case Right(f) => post(settlementId, approver, h, f)
        }
    }

  private def head(id: UUID): ConnectionIO[Option[SettleHead]] =
    sql"""SELECT procurement_entity_id, operating_entity_id, txn_currency, functional_currency, as_of, status, proposed_by
          FROM ic_settlement WHERE id = $id"""
      .query[SettleHead]
      .option

  private def figures(h: SettleHead): ConnectionIO[Either[String, SettleFigures]] =
    (
      sql"""SELECT COALESCE(SUM(uplift_total - returned_uplift), 0),
                   COALESCE(SUM(CASE WHEN rate_source LIKE 'hedge:%' THEN uplift_total - returned_uplift ELSE 0 END), 0),
                   COALESCE(SUM((uplift_total - returned_uplift) * COALESCE(booked_rate, 1)), 0)
            FROM ic_match
            WHERE procurement_entity_id = ${h.pr} AND operating_entity_id = ${h.op} AND currency = ${h.txnCcy}
              AND reversed_at IS NULL AND settlement_id IS NULL"""
        .query[(BigDecimal, BigDecimal, BigDecimal)]
        .unique,
      // the LIVE adjunct position, telescoped across prior settlements (each consumed its prior-at-settle)
      sql"""SELECT COALESCE((SELECT SUM(delta) FROM ic_remeasurement
                             WHERE procurement_entity_id = ${h.pr} AND operating_entity_id = ${h.op}), 0)
                 - COALESCE((SELECT SUM(prior_deltas_at_settle) FROM ic_settlement
                             WHERE procurement_entity_id = ${h.pr} AND operating_entity_id = ${h.op}
                               AND status = 'settled'), 0)"""
        .query[BigDecimal]
        .unique,
      sql"""SELECT rate, as_of::text FROM exchange_rate
            WHERE base = ${h.txnCcy} AND quote = ${h.fnCcy} AND rate_type = 'spot' AND as_of <= ${h.asOf}
            ORDER BY as_of DESC LIMIT 1"""
        .query[(BigDecimal, String)]
        .option
    ).mapN { (sums, prior, spot) =>
      val (net, hedged, bookedFn) = sums
      val unhedged                = net - hedged
      if (minor(net.abs) == 0) "nothing to settle for this pair".asLeft[SettleFigures]
      else if (h.txnCcy == h.fnCcy)
        SettleFigures(net, hedged, net, prior, None, "identity", net, BigDecimal(0)).asRight[String]
      else
        spot match {
          case None if minor(unhedged.abs) > 0 =>
            s"no ${h.txnCcy}->${h.fnCcy} spot rate on or before ${h.asOf} — settlement fails closed (doc 28 §5.4)"
              .asLeft[SettleFigures]
          case maybeRate =>
            // settled/realized are finalized in post() with the exact hedged-booked split
            SettleFigures(
              net,
              hedged,
              bookedFn.setScale(4, RoundingMode.HALF_UP),
              prior,
              maybeRate.map(_._1),
              maybeRate.fold("hedged-only")(r => s"spot:${r._2}"),
              BigDecimal(0),
              BigDecimal(0)
            ).asRight[String]
        }
    }

  private def post(sid: UUID, approver: UUID, h: SettleHead, f0: SettleFigures): F[Either[String, SettlementResult]] =
    Currency.fromCode(h.txnCcy) match {
      case None => s"unknown currency ${h.txnCcy}".asLeft[SettlementResult].pure[F]
      case Some(txn) =>
        hedgedBooked(h).transact(xa).flatMap { hb =>
          val f          = recompute(h, f0, hb)
          val fnCurrency = Currency.fromCode(h.fnCcy).getOrElse(txn)
          val txnLedger  = Ledgers.forCurrency(txn)
          val fnLedger   = Ledgers.forCurrency(fnCurrency)
          val icAp       = JournalAccount(s"IC_AP:${h.op}:${h.pr}", LedgerAccountCode.Intercompany, Some(h.op))
          val icAr       = JournalAccount(s"IC_AR:${h.pr}:${h.op}", LedgerAccountCode.Intercompany, Some(h.pr))
          val opBank     = JournalAccount(s"BANK:${h.op}", LedgerAccountCode.Bank, Some(h.op))
          val prBank     = JournalAccount(s"BANK:${h.pr}", LedgerAccountCode.Bank, Some(h.pr))
          val adjunct    = JournalAccount(s"IC_AR_REMEASURE:${h.pr}:${h.op}", LedgerAccountCode.IcRemeasure, Some(h.pr))
          val fxPnl      = JournalAccount(s"FX_GAINLOSS:${h.pr}", LedgerAccountCode.FxGainLoss, Some(h.pr))
          val fxSettled  = JournalAccount(s"FX_SETTLED:${h.pr}", LedgerAccountCode.FxClearing, Some(h.pr))
          val netAbs     = minor(f.netTxn.abs)
          // net > 0: op pays the principal; net < 0 (below-cost-dominated): the principal pays back
          val (opD, opC) = if (f.netTxn >= 0) (icAp, opBank) else (opBank, icAp)
          val (prD, prC) = if (f.netTxn >= 0) (prBank, icAr) else (icAr, prBank)
          val finalDelta = f.realizedFx - f.priorDeltas
          val (fdD, fdC) = if (finalDelta >= 0) (adjunct, fxPnl) else (fxPnl, adjunct)
          val (rcD, rcC) = if (f.realizedFx >= 0) (fxSettled, adjunct) else (adjunct, fxSettled)
          val postings = List(
            Posting(sid, 0, opD, opC, txn, netAbs, transferCode = LedgerTransferCode.Settlement),
            Posting(sid, 1, prD, prC, txn, netAbs, transferCode = LedgerTransferCode.Settlement),
            Posting(sid, 2, fdD, fdC, fnCurrency, minor(finalDelta.abs), transferCode = LedgerTransferCode.Settlement),
            Posting(sid, 3, rcD, rcC, fnCurrency, minor(f.realizedFx.abs), transferCode = LedgerTransferCode.Settlement)
          )
          val accounts = List(
            LedgerAccount(TbIds.accountId(icAp.key), txnLedger, LedgerAccountCode.Intercompany),
            LedgerAccount(TbIds.accountId(icAr.key), txnLedger, LedgerAccountCode.Intercompany),
            LedgerAccount(TbIds.accountId(opBank.key), txnLedger, LedgerAccountCode.Bank),
            LedgerAccount(TbIds.accountId(prBank.key), txnLedger, LedgerAccountCode.Bank),
            LedgerAccount(TbIds.accountId(adjunct.key), fnLedger, LedgerAccountCode.IcRemeasure),
            LedgerAccount(TbIds.accountId(fxPnl.key), fnLedger, LedgerAccountCode.FxGainLoss),
            LedgerAccount(TbIds.accountId(fxSettled.key), fnLedger, LedgerAccountCode.FxClearing)
          )
          ledger.createAccounts(accounts) *>
            journal.post(Instant.now(), postings) *>
            record(sid, approver, h, f)
              .transact(xa)
              .as(
                SettlementResult(sid, f.netTxn, f.hedgedTxn, f.bookedFn, f.settledFn, f.realizedFx).asRight[String]
              )
        }
    }

  private def recompute(h: SettleHead, f: SettleFigures, hedgedBookedFn: BigDecimal): SettleFigures =
    if (h.txnCcy == h.fnCcy) f
    else {
      val unhedged = f.netTxn - f.hedgedTxn
      val settledFn =
        (hedgedBookedFn + f.settledRate.fold(BigDecimal(0))(unhedged * _)).setScale(4, RoundingMode.HALF_UP)
      f.copy(settledFn = settledFn, realizedFx = (settledFn - f.bookedFn).setScale(4, RoundingMode.HALF_UP))
    }

  private def hedgedBooked(h: SettleHead): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM((uplift_total - returned_uplift) * COALESCE(booked_rate, 1)), 0)
          FROM ic_match
          WHERE procurement_entity_id = ${h.pr} AND operating_entity_id = ${h.op} AND currency = ${h.txnCcy}
            AND reversed_at IS NULL AND settlement_id IS NULL AND rate_source LIKE 'hedge:%'"""
      .query[BigDecimal]
      .unique

  private def record(sid: UUID, approver: UUID, h: SettleHead, f: SettleFigures): ConnectionIO[Unit] = {
    val claim = (leg: Int, amount: BigDecimal) =>
      Option.when(minor(amount.abs) > 0)(BigDecimal(TbIds.transferId(sid, leg)))
    val finalDelta = f.realizedFx - f.priorDeltas
    for {
      // settlement consumes the hedge: the live drawdown releases, the notional stays used (it executed)
      _ <- sql"""UPDATE fx_hedge x
                 SET ic_drawdown = x.ic_drawdown - sub.exposure
                 FROM (SELECT h2.id, COALESCE(SUM(m.uplift_total - m.returned_uplift), 0) AS exposure
                       FROM fx_hedge h2 JOIN ic_match m ON m.rate_source = 'hedge:' || h2.id::text
                       WHERE m.procurement_entity_id = ${h.pr} AND m.operating_entity_id = ${h.op}
                         AND m.currency = ${h.txnCcy} AND m.reversed_at IS NULL AND m.settlement_id IS NULL
                       GROUP BY h2.id) sub
                 WHERE x.id = sub.id""".update.run
      _ <- sql"""UPDATE ic_match SET settlement_id = $sid, settled_at = now()
                 WHERE procurement_entity_id = ${h.pr} AND operating_entity_id = ${h.op}
                   AND currency = ${h.txnCcy} AND reversed_at IS NULL AND settlement_id IS NULL""".update.run
      _ <- sql"""UPDATE ic_settlement
                 SET status = 'settled', settled_at = now(), approved_by = $approver,
                     net_txn = ${f.netTxn}, hedged_txn = ${f.hedgedTxn}, booked_functional = ${f.bookedFn},
                     settled_rate = ${f.settledRate}, rate_source = ${f.rateSource},
                     settled_functional = ${f.settledFn}, realized_fx = ${f.realizedFx},
                     prior_deltas_at_settle = ${f.priorDeltas},
                     op_cash_tb_transfer_id = ${claim(0, f.netTxn)}, pr_cash_tb_transfer_id = ${claim(1, f.netTxn)},
                     fx_final_tb_transfer_id = ${claim(2, finalDelta)},
                     fx_reclass_tb_transfer_id = ${claim(3, f.realizedFx)}
                 WHERE id = $sid""".update.run
    } yield ()
  }
}
