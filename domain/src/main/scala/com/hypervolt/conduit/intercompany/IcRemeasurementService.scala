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

final case class RemeasureLine(
    procurementEntity: UUID,
    operatingEntity: UUID,
    txnCurrency: String,
    functionalCurrency: String,
    openTxn: BigDecimal,
    closingRate: BigDecimal,
    carryingBefore: BigDecimal,
    measured: BigDecimal,
    delta: BigDecimal,
    posted: Boolean
)

// ASC 830-20-35 period-end remeasurement (spec doc 28 §5.3), delta method. The open IC balance per
// (principal, operating, currency) pair — Σ(uplift − returned) over unreversed matches — is measured at the
// closing rate; only the DELTA since the last remeasurement posts, to the PRINCIPAL'S FUNCTIONAL ledger
// (DR remeasurement adjunct / CR FX_GAINLOSS on a gain; flipped on a loss). The transaction-currency ledger
// is untouched truth. A fully-voided pair remeasures its cumulative deltas back to exactly zero — the void
// law (L2) extends through remeasurement. Missing closing rate at close FAILS CLOSED, like everything else.
final class IcRemeasurementService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val journal = new Journal[F](xa, ledger)

  private def minor(a: BigDecimal): BigInt = (a.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt

  def run(asOf: LocalDate): F[Either[String, List[RemeasureLine]]] =
    pairs.transact(xa).flatMap(_.traverse(remeasure(asOf, _)).map(_.sequence.map(_.flatten)))

  // Every (principal, operating, txn ccy) pair with cross-currency exposure remeasures (ASC 830); pairs with
  // remeasurement history stay in, so a fully-voided/settled pair trues its cumulative deltas back to zero.
  private def pairs: ConnectionIO[List[(UUID, UUID, String, String)]] =
    sql"""SELECT DISTINCT m.procurement_entity_id, m.operating_entity_id, m.currency, e.functional_currency
          FROM ic_match m JOIN entity e ON e.id = m.procurement_entity_id
          WHERE e.functional_currency <> m.currency
          UNION
          SELECT r.procurement_entity_id, r.operating_entity_id, r.txn_currency, r.functional_currency
          FROM ic_remeasurement r"""
      .query[(UUID, UUID, String, String)]
      .to[List]

  private def remeasure(
      asOf: LocalDate,
      pair: (UUID, UUID, String, String)
  ): F[Either[String, Option[RemeasureLine]]] = {
    val (pr, op, txnCcy, fnCcy) = pair
    inputs(pr, op, txnCcy, fnCcy, asOf).transact(xa).flatMap {
      case Left(e) => e.asLeft[Option[RemeasureLine]].pure[F]
      case Right((openTxn, bookedFunctional, priorDeltas, rate, rateSource)) =>
        val carrying = bookedFunctional + priorDeltas
        val measured = (openTxn * rate).setScale(4, RoundingMode.HALF_UP)
        val delta    = measured - carrying
        val line     = RemeasureLine(pr, op, txnCcy, fnCcy, openTxn, rate, carrying, measured, delta, minor(delta.abs) > 0)
        if (minor(delta.abs) == 0)
          record(UUID.randomUUID(), line, asOf, rateSource, claimed = false).transact(xa).as(line.some.asRight[String])
        else
          Currency.fromCode(fnCcy) match {
            case None => s"unknown functional currency $fnCcy".asLeft[Option[RemeasureLine]].pure[F]
            case Some(ccy) =>
              val rowId   = UUID.randomUUID()
              val gbpLike = Ledgers.forCurrency(ccy)
              val adjunct = JournalAccount(s"IC_AR_REMEASURE:$pr:$op", LedgerAccountCode.IcRemeasure, Some(pr))
              val fx      = JournalAccount(s"FX_GAINLOSS:$pr", LedgerAccountCode.FxGainLoss, Some(pr))
              // gain (receivable worth more in functional terms): DR adjunct / CR FX gain; loss flips
              val (debit, credit) = if (delta > 0) (adjunct, fx) else (fx, adjunct)
              ledger.createAccounts(
                List(
                  LedgerAccount(TbIds.accountId(adjunct.key), gbpLike, LedgerAccountCode.IcRemeasure),
                  LedgerAccount(TbIds.accountId(fx.key), gbpLike, LedgerAccountCode.FxGainLoss)
                )
              ) *>
                journal.postOne(
                  Instant.now(),
                  Posting(rowId, 0, debit, credit, ccy, minor(delta.abs), transferCode = LedgerTransferCode.Remeasure)
                ) *>
                record(rowId, line, asOf, rateSource, claimed = true).transact(xa).as(line.some.asRight[String])
          }
    }
  }

  private def inputs(
      pr: UUID,
      op: UUID,
      txnCcy: String,
      fnCcy: String,
      asOf: LocalDate
  ): ConnectionIO[Either[String, (BigDecimal, BigDecimal, BigDecimal, BigDecimal, String)]] =
    (
      // every open IC monetary balance remeasures at spot through earnings (ASC 830, doc 28 §5.3); only
      // settled matches are GONE. The hedge does NOT freeze the balance — it offsets via its own MTM (4b).
      sql"""SELECT COALESCE(SUM(uplift_total - returned_uplift), 0),
                   COALESCE(SUM((uplift_total - returned_uplift) * COALESCE(booked_rate, 1)), 0)
            FROM ic_match
            WHERE procurement_entity_id = $pr AND operating_entity_id = $op AND currency = $txnCcy
              AND reversed_at IS NULL AND settlement_id IS NULL"""
        .query[(BigDecimal, BigDecimal)]
        .unique,
      // the LIVE adjunct position: settlements consumed their prior-at-settle (the reclass cleared it)
      sql"""SELECT COALESCE((SELECT SUM(delta) FROM ic_remeasurement
                             WHERE procurement_entity_id = $pr AND operating_entity_id = $op), 0)
                 - COALESCE((SELECT SUM(prior_deltas_at_settle) FROM ic_settlement
                             WHERE procurement_entity_id = $pr AND operating_entity_id = $op
                               AND status = 'settled'), 0)"""
        .query[BigDecimal]
        .unique,
      sql"""SELECT rate, as_of::text FROM exchange_rate
            WHERE base = $txnCcy AND quote = $fnCcy AND rate_type = 'closing' AND as_of <= $asOf
            ORDER BY as_of DESC LIMIT 1"""
        .query[(BigDecimal, String)]
        .option
    ).mapN {
      case (_, _, None) =>
        s"no $txnCcy->$fnCcy closing rate on or before $asOf — remeasurement fails closed (doc 28 §5.3)"
          .asLeft[(BigDecimal, BigDecimal, BigDecimal, BigDecimal, String)]
      case ((openTxn, bookedFn), prior, Some((rate, rateDate))) =>
        (openTxn, bookedFn.setScale(4, RoundingMode.HALF_UP), prior, rate, s"closing:$rateDate")
          .asRight[String]
    }

  private def record(
      rowId: UUID,
      l: RemeasureLine,
      asOf: LocalDate,
      rateSource: String,
      claimed: Boolean
  ): ConnectionIO[Int] = {
    val claim = Option.when(claimed)(BigDecimal(TbIds.transferId(rowId, 0)))
    sql"""INSERT INTO ic_remeasurement
            (id, procurement_entity_id, operating_entity_id, txn_currency, functional_currency, as_of,
             open_txn, closing_rate, rate_source, carrying_before, measured, delta, tb_transfer_id)
          VALUES ($rowId, ${l.procurementEntity}, ${l.operatingEntity}, ${l.txnCurrency}, ${l.functionalCurrency},
                  $asOf, ${l.openTxn}, ${l.closingRate}, $rateSource, ${l.carryingBefore}, ${l.measured},
                  ${l.delta}, $claim)""".update.run
  }
}
