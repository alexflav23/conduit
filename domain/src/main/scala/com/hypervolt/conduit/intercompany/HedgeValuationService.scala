package com.hypervolt.conduit.intercompany

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
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
    pairFrom: String,
    pairTo: String,
    contracted: BigDecimal,
    open: BigDecimal
)

// Hedge performance + ASC 815-50 / Item 305 disclosure (spec doc 28 §5.5). Each period, value every active
// hedge at the period spot and record its fair value — the gain/loss vs the contracted rate — so treasury
// sees how each individual hedge performs, per market. Measurement only: no ledger posting (slice 4b posts
// the offsetting MTM through earnings). `designate` enforces the ASC 815 inception-documentation rule:
// classifying a hedge as cash_flow / net_investment requires a doc_ref, fail-closed — economic is the
// undesignated GAAP default and needs none.
final class HedgeValuationService[F[_]: Async](xa: Transactor[F]) {

  private def round4(b: BigDecimal): BigDecimal = b.setScale(4, RoundingMode.HALF_UP)

  // Value all active hedges as of `asOf` at the latest spot ≤ asOf for the pair. A hedge with no spot rate
  // is skipped (it cannot be valued yet) rather than guessed — fail-closed on measurement too.
  def revalue(asOf: LocalDate): F[List[HedgeValuation]] =
    activeHedges.transact(xa).flatMap(_.flatTraverse(valueOne(_, asOf)))

  private def activeHedges: ConnectionIO[List[HvHedgeRow]] =
    sql"""SELECT id, pair_from, pair_to, contracted_rate, (notional - notional_used)
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
        sql"""INSERT INTO hedge_valuation
                (fx_hedge_id, as_of, spot_rate, contracted_rate, notional_open, period_mtm, cumulative_mtm)
              VALUES (${h.id}, $asOf, $spot, ${h.contracted}, ${h.open}, $period, $cumulative)""".update.run
          .transact(xa)
          .as(List(HedgeValuation(h.id, asOf, spot, h.contracted, h.open, period, cumulative)))
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
