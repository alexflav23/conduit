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

final case class HedgeValuation(
    hedgeId: UUID,
    asOf: LocalDate,
    spot: BigDecimal,
    contracted: BigDecimal,
    notionalOpen: BigDecimal,
    periodMtm: BigDecimal,
    cumulativeMtm: BigDecimal
)

private final case class HvHedgeRow(
    id: UUID,
    entityId: UUID,
    pairFrom: String,
    pairTo: String,
    contracted: BigDecimal,
    open: BigDecimal
)

// Hedge performance + the economic mark-to-market (spec doc 28 §5.5, ASC 815 / 4b). Each period, value every
// active hedge at the period spot, record its fair value — the gain/loss vs the contracted rate — AND post
// the period MTM through earnings: DR FX_DERIVATIVE (the instrument's balance-sheet fair value) / CR
// FX_GAINLOSS on a gain, flipped on a loss, in the principal's functional currency. That MTM OFFSETS the
// ASC-830 remeasurement of the IC balance (which now floats at spot) — both hit earnings, the economic
// treatment GAAP requires for hedging a recognized monetary balance. `designate` enforces the ASC 815-20-25
// inception-documentation rule (cash_flow / net_investment need a doc_ref, fail-closed; economic is default).
final class HedgeValuationService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val journal                           = new Journal[F](xa, ledger)
  private def round4(b: BigDecimal): BigDecimal = b.setScale(4, RoundingMode.HALF_UP)
  private def minor(a: BigDecimal): BigInt      = (a.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt

  // Value all active hedges as of `asOf` at the latest spot ≤ asOf for the pair. A hedge with no spot rate
  // is skipped (it cannot be valued yet) rather than guessed — fail-closed on measurement too.
  def revalue(asOf: LocalDate): F[List[HedgeValuation]] =
    activeHedges.transact(xa).flatMap(_.flatTraverse(valueOne(_, asOf)))

  private def activeHedges: ConnectionIO[List[HvHedgeRow]] =
    sql"""SELECT id, entity_id, pair_from, pair_to, contracted_rate, (notional - notional_used)
          FROM fx_hedge WHERE status = 'active'"""
      .query[HvHedgeRow]
      .to[List]

  private def valueOne(h: HvHedgeRow, asOf: LocalDate): F[List[HedgeValuation]] =
    (spotRate(h.pairFrom, h.pairTo, asOf), priorCumulative(h.id)).tupled.transact(xa).flatMap {
      case (None, _)           => List.empty[HedgeValuation].pure[F]
      case (Some(spot), prior) =>
        // the hedge's gain vs inception: locked high, spot fell ⇒ in-the-money (contracted − spot) × open
        val cumulative = round4((h.contracted - spot) * h.open)
        val period     = round4(cumulative - prior)
        val rowId      = UUID.randomUUID()
        // post the PERIOD delta through earnings (economic MTM, 4b), in the functional currency (pair_to)
        val posting = Currency.fromCode(h.pairTo) match {
          case Some(ccy) if minor(period.abs) > 0 =>
            val led   = Ledgers.forCurrency(ccy)
            val deriv = JournalAccount(s"FX_DERIVATIVE:${h.entityId}", LedgerAccountCode.FxDerivative, Some(h.entityId))
            val fx    = JournalAccount(s"FX_GAINLOSS:${h.entityId}", LedgerAccountCode.FxGainLoss, Some(h.entityId))
            // gain: the instrument's fair value rises (DR derivative / CR FX gain); loss flips
            val (debit, credit) = if (period > 0) (deriv, fx) else (fx, deriv)
            ledger.createAccounts(
              List(
                LedgerAccount(TbIds.accountId(deriv.key), led, LedgerAccountCode.FxDerivative),
                LedgerAccount(TbIds.accountId(fx.key), led, LedgerAccountCode.FxGainLoss)
              )
            ) *> journal.postOne(
              Instant.now(),
              Posting(rowId, 0, debit, credit, ccy, minor(period.abs), transferCode = LedgerTransferCode.HedgeMtm)
            ) *> Option(BigDecimal(TbIds.transferId(rowId, 0))).pure[F]
          case _ => Option.empty[BigDecimal].pure[F]
        }
        posting.flatMap { claim =>
          sql"""INSERT INTO hedge_valuation
                  (id, fx_hedge_id, as_of, spot_rate, contracted_rate, notional_open, period_mtm, cumulative_mtm, tb_transfer_id)
                VALUES ($rowId, ${h.id}, $asOf, $spot, ${h.contracted}, ${h.open}, $period, $cumulative, $claim)""".update.run
            .transact(xa)
            .as(List(HedgeValuation(h.id, asOf, spot, h.contracted, h.open, period, cumulative)))
        }
    }

  private def spotRate(from: String, to: String, asOf: LocalDate): ConnectionIO[Option[BigDecimal]] =
    sql"""SELECT rate FROM exchange_rate
          WHERE base = $from AND quote = $to AND rate_type IN ('spot', 'closing') AND as_of <= $asOf
          ORDER BY as_of DESC LIMIT 1"""
      .query[BigDecimal]
      .option

  private def priorCumulative(hedgeId: UUID): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(period_mtm), 0) FROM hedge_valuation WHERE fx_hedge_id = $hedgeId"""
      .query[BigDecimal]
      .unique

  // ASC 815-20-25: hedge accounting requires contemporaneous inception documentation. Designating a hedge
  // cash_flow / net_investment without a doc_ref FAILS CLOSED; economic (the default) needs none.
  def designate(hedgeId: UUID, designation: String, docRef: Option[String]): F[Either[String, Unit]] =
    if (designation != "economic" && docRef.forall(_.trim.isEmpty))
      s"'$designation' hedge accounting requires inception documentation (doc_ref) — fails closed (ASC 815-20-25)"
        .asLeft[Unit]
        .pure[F]
    else if (!Set("economic", "cash_flow", "net_investment").contains(designation))
      s"unknown hedge designation '$designation'".asLeft[Unit].pure[F]
    else
      sql"UPDATE fx_hedge SET designation = $designation, doc_ref = $docRef WHERE id = $hedgeId".update.run
        .transact(xa)
        .as(().asRight[String])
}
