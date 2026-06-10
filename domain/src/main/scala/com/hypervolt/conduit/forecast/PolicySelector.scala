package com.hypervolt.conduit.forecast

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// One scored cell of the error ledger, the selection evidence (doc 26 §5): what `modelKey` forecast for
// (origin, period) and what actually happened. Strictly censored by the caller (origin < selection origin).
final case class PolicyEvidence(
    origin: LocalDate,
    period: LocalDate,
    modelKey: String,
    forecast: BigDecimal,
    actual: BigDecimal
)

// A forecasting policy: a weighted combination of registry models (a single model is the one-member case).
// Selected mechanically by the tournament, applied blind to the future — the policy never sees the quarter
// it is asked to predict.
final case class Policy(weights: Map[String, BigDecimal]) {

  def key: String =
    if (weights.size == 1) weights.head._1
    else "blend(" + weights.keys.toList.sorted.mkString("+") + ")"

  def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal] = {
    val members = weights.toList.flatMap {
      case (k, w) => DemandModel.registry.find(_.key == k).map(m => (m.predictClamped(h, horizon), w))
    }
    if (members.isEmpty) DemandModel.SeasonalNaive.predict(h, horizon)
    else
      Vector.tabulate(horizon)(i =>
        members
          .map { case (preds, w) => preds(i) * w }
          .foldLeft(BigDecimal(0))(_ + _)
          .setScale(4, RoundingMode.HALF_UP)
      )
  }
}

object Policy {
  def single(modelKey: String): Policy = Policy(Map(modelKey -> BigDecimal(1)))
}

object PolicyRepo {

  // The censored evidence for a company: every scored (origin, period, model) cell strictly before the
  // selection origin — the same no-leakage rule the predictions themselves obey. Aggregated to the ACCOUNT
  // grain (summed over the account's SKUs): selection is per account, and an unaggregated row set would let
  // the selector see one arbitrary variant's numbers per cell.
  def evidence(company: UUID, before: LocalDate): ConnectionIO[List[PolicyEvidence]] =
    sql"""SELECT origin_month, period_month, model_key, SUM(forecast_qty), SUM(actual_qty)
          FROM model_accuracy WHERE company_id = $company AND origin_month < $before
          GROUP BY origin_month, period_month, model_key"""
      .query[PolicyEvidence]
      .to[List]
}

// The per-series technique tournament (doc 26 §5, promoted from the report-side prototype): candidates are
// every single registry model plus inverse-WAPE blends of the top-2 and top-3 singles; each candidate is
// scored on ALL prior origins (small selection windows proved unstable); the stability band prefers the
// candidate with the best worst-origin among near-ties; and the variance guard demotes any winner whose
// worst origin still blew up to the robust run-rate — the thin-channel (Automotive) failure mode.
object PolicySelector {

  private val Fallback           = DemandModel.RunRate3.key
  private val StabilityBand      = BigDecimal("1.1") // near-ties: within 10% of the best pooled WAPE
  private val GuardWape          = BigDecimal("1.5") // a worst origin past 150% error = unstable series
  private val UnforecastableWape = BigDecimal("0.5") // even the winner pools >50% = nothing here extrapolates
  private val DefaultMinOrigin   = 3
  private val IncumbentEdge      = BigDecimal("0.8") // a STATISTICAL challenger must beat the run-rate by >20%
  private val StructuralEdge     = BigDecimal("1.0") // structure (telemetry/book) only has to beat it at all —
  // the 20% hurdle exists to stop curve-fit selection, and a shelf measurement is not a curve fit

  private final case class Scored(policy: Policy, pooled: BigDecimal, worstOrigin: BigDecimal)

  def select(evidence: List[PolicyEvidence], minOrigins: Int = DefaultMinOrigin): Policy = {
    val cells = evidence
      .groupBy(e => (e.origin, e.period))
      .map { case (k, es) => (k, (es.head.actual, es.map(e => e.modelKey -> e.forecast).toMap)) }
    val origins = cells.keys.map(_._1).toSet
    val complete = evidence
      .groupBy(_.modelKey)
      .collect { case (k, es) if es.map(e => (e.origin, e.period)).toSet.size == cells.size => k }
      .toList

    if (cells.isEmpty || complete.isEmpty) Policy.single(DemandModel.SeasonalNaive.key)
    else if (origins.size < minOrigins) {
      // thin evidence gets the same bounded-badness protection as the tournament: a winner whose own
      // (small) record pools past the unforecastable line must not extrapolate — the level run-rate does
      val singles = complete.map(k => score(Policy.single(k), cells))
      val winner  = singles.minBy(s => (s.pooled, s.policy.key))
      val rr      = singles.find(_.policy.key == Fallback)
      if (winner.pooled > UnforecastableWape || rr.exists(winner.pooled > _.pooled * edgeFor(winner)))
        Policy.single(Fallback)
      else winner.policy
    } else {
      // an origin even the BEST single missed by >150% is an anomaly quarter (a one-off collapse, a data
      // artifact) — it punishes every candidate and drowns the regular-quarter signal, so it is dropped
      // from the selection evidence, provided enough origins remain to select on.
      val anomalous = origins.filter(o =>
        complete.map(k => perOriginRel(Policy.single(k), cells).getOrElse(o, BigDecimal(0))).min > GuardWape
      )
      val effective =
        if (origins.size - anomalous.size >= minOrigins)
          cells.view.filterKeys { case (o, _) => !anomalous(o) }.toMap
        else cells
      val singles = complete.map(k => score(Policy.single(k), effective)).sortBy(s => (s.pooled, s.policy.key))
      tournament(singles, effective)
    }
  }

  private def perOriginRel(
      policy: Policy,
      cells: Map[(LocalDate, LocalDate), (BigDecimal, Map[String, BigDecimal])]
  ): Map[LocalDate, BigDecimal] =
    cells.toList
      .map {
        case ((origin, _), (actual, forecasts)) =>
          val blended = policy.weights.toList
            .map { case (k, w) => forecasts.getOrElse(k, BigDecimal(0)) * w }
            .foldLeft(BigDecimal(0))(_ + _)
          (origin, blended, actual)
      }
      .groupBy(_._1)
      .map {
        case (o, months) =>
          val f = months.map(_._2).foldLeft(BigDecimal(0))(_ + _)
          val a = months.map(_._3).foldLeft(BigDecimal(0))(_ + _)
          o -> (f - a).abs / a.max(BigDecimal(1))
      }

  private def tournament(
      singles: List[Scored],
      cells: Map[(LocalDate, LocalDate), (BigDecimal, Map[String, BigDecimal])]
  ): Policy = {
    val blends = List(2, 3)
      .filter(_ <= singles.size)
      .map(n => score(inverseWapeBlend(singles.take(n)), cells))
    // the structural hedge: each structural model (order book, retail funnel) paired with each top-2
    // statistical — structure that explains PART of a channel earns PART of the weight, even when it
    // can't win the pooled score alone
    val structuralKeys = StructuralModels
    val hedges = singles
      .filter(s => structuralKeys(s.policy.key))
      .filterNot(singles.take(3).contains)
      .flatMap(s => singles.take(2).map(st => score(inverseWapeBlend(List(st, s)), cells)))
    val candidates = singles ++ blends ++ hedges
    val best       = candidates.map(_.pooled).min
    val winner = candidates
      .filter(_.pooled <= best * StabilityBand)
      .minBy(s => (s.worstOrigin, s.policy.weights.size, s.policy.key))
    // unstable winner OR an unforecastable series: a trend model fit on noise extrapolates the noise —
    // the bounded-badness answer is the level run-rate, which re-bases as fast as the channel does.
    // And the run-rate is the INCUMBENT (measured: at the account grain it beat the unconstrained
    // tournament on both means) — a challenger takes a series only by beating it >20% on censored evidence.
    val incumbent = singles.find(_.policy.key == Fallback)
    val champion =
      if (winner.worstOrigin > GuardWape || winner.pooled > UnforecastableWape) Policy.single(Fallback)
      else
        incumbent match {
          case Some(inc) if winner.pooled > inc.pooled * edgeFor(winner) => Policy.single(Fallback)
          case _                                                         => winner.policy
        }
    champion
  }

  private val StructuralModels = Set(
    DemandModel.OrderBook.key,
    DemandModel.RetailFunnel.key,
    DemandModel.RetailFunnelMomentum.key,
    DemandModel.Depletion.key,
    DemandModel.PantryReversal.key
  )

  private def edgeFor(winner: Scored): BigDecimal =
    if (winner.policy.weights.keys.exists(StructuralModels)) StructuralEdge else IncumbentEdge

  private def inverseWapeBlend(top: List[Scored]): Policy = {
    val inv   = top.map(s => s.policy.key -> BigDecimal(1) / s.pooled.max(BigDecimal("0.0001")))
    val total = inv.map(_._2).foldLeft(BigDecimal(0))(_ + _)
    Policy(inv.map { case (k, w) => k -> (w / total).setScale(6, RoundingMode.HALF_UP) }.toMap)
  }

  // Candidates are judged at the QUARTER grain — each origin's horizon months sum to one number before the
  // error is taken. Monthly cells let a model win on within-quarter shape while missing the quarter total,
  // and the quarter total is what the business reads (doc 26 §5: score at the served grain).
  private def score(
      policy: Policy,
      cells: Map[(LocalDate, LocalDate), (BigDecimal, Map[String, BigDecimal])]
  ): Scored = {
    val perOrigin = cells.toList
      .map {
        case ((origin, _), (actual, forecasts)) =>
          val blended = policy.weights.toList
            .map { case (k, w) => forecasts.getOrElse(k, BigDecimal(0)) * w }
            .foldLeft(BigDecimal(0))(_ + _)
          (origin, blended, actual)
      }
      .groupBy(_._1)
      .values
      .map { months =>
        val f = months.map(_._2).foldLeft(BigDecimal(0))(_ + _)
        val a = months.map(_._3).foldLeft(BigDecimal(0))(_ + _)
        ((f - a).abs, a)
      }
      .toList
    val pooled = perOrigin.map(_._1).foldLeft(BigDecimal(0))(_ + _) /
      perOrigin.map(_._2).foldLeft(BigDecimal(0))(_ + _).max(BigDecimal(1))
    val worst = perOrigin.map { case (err, a) => err / a.max(BigDecimal(1)) }.max
    Scored(policy, pooled, worst)
  }
}
