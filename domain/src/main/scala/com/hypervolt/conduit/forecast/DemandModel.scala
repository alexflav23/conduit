package com.hypervolt.conduit.forecast

import java.time.LocalDate
import scala.math.BigDecimal.RoundingMode

// One account×SKU(×market) monthly unit history, contiguous and zero-filled up to (exclusive of) the origin —
// the CENSORED view the backtest loop fits on (doc 26 §5: every feature knowable strictly before the origin).
final case class DemandHistory(
    months: Vector[LocalDate], // month starts, ascending, contiguous
    qty: Vector[BigDecimal],   // same length; zero-filled where no demand
    // the depletion context (doc 26 §4) — present when the account has activation telemetry, as-of the origin
    shelfStock: Option[BigDecimal] = None,         // shipped − activated, at origin
    activationVelocity: Option[BigDecimal] = None, // units activated / month (seasonally adjustable), at origin
    // the order-book context (doc 26 §4a) — present when the series maps to a deal pipeline, as-of the origin
    openBook: Option[BigDecimal] = None,        // open deal amount at the origin
    bookConversion: Option[BigDecimal] = None,  // share of an open book won within a quarter (pre-origin cohorts)
    newBusinessQ: Option[BigDecimal] = None,    // created-and-won-within-quarter run-rate (pre-origin quarters)
    funnelExpectedQ: Option[BigDecimal] = None, // the retail-funnel composed quarter expectation (doc 26 §4a)
    funnelMomentumQ: Option[BigDecimal] = None  // the same funnel with created volume at last month's rate × 3
) {
  def nonEmpty: Boolean = qty.exists(_ > 0)
}

// A pure, deterministic demand model (doc 26 §4): fit on a censored history, predict `horizon` months of unit
// demand. No randomness, no clock, no IO — (history, horizon) fully determines the output, so every backtest
// run is reproducible forever. Negative forecasts are clamped to zero by every family.
trait DemandModel {
  def key: String
  def version: Int
  def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal]
}

object DemandModel {

  private val Scale                        = 4
  private def r(x: BigDecimal): BigDecimal = x.setScale(Scale, RoundingMode.HALF_UP).max(BigDecimal(0))
  private def mean(xs: Vector[BigDecimal]): BigDecimal =
    if (xs.isEmpty) BigDecimal(0) else xs.sum / xs.length

  // The baseline every model must beat (doc 26 §4): same month last year; falls back to the trailing-3 mean.
  object SeasonalNaive extends DemandModel {
    val key     = "seasonal_naive"
    val version = 1
    def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal] = {
      val fallback = mean(h.qty.takeRight(3))
      Vector.tabulate(horizon) { i =>
        val idx = h.qty.length + i - 12
        r(if (idx >= 0 && idx < h.qty.length) h.qty(idx) else fallback)
      }
    }
  }

  // Exponentially-weighted level, flat forecast — dense, stable accounts.
  final class Ewma(alpha: BigDecimal) extends DemandModel {
    val key     = "ewma"
    val version = 1
    def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal] = {
      val level = h.qty.foldLeft(Option.empty[BigDecimal]) {
        case (None, x)    => Some(x)
        case (Some(l), x) => Some(alpha * x + (1 - alpha) * l)
      }
      Vector.fill(horizon)(r(level.getOrElse(BigDecimal(0))))
    }
  }

  // Croston with the SBA correction — intermittent/lumpy B2B demand: EWMA the non-zero sizes and the
  // inter-demand intervals separately; forecast = (1 − α/2) · size / interval.
  final class CrostonSba(alpha: BigDecimal) extends DemandModel {
    val key     = "croston_sba"
    val version = 1
    def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal] = {
      final case class S(size: Option[BigDecimal], interval: Option[BigDecimal], gap: Int)
      val s = h.qty.foldLeft(S(None, None, 0)) { (acc, x) =>
        if (x <= 0) acc.copy(gap = acc.gap + 1)
        else {
          val iv   = BigDecimal(acc.gap + 1)
          val size = acc.size.fold(x)(z => alpha * x + (1 - alpha) * z)
          val intv = acc.interval.fold(iv)(p => alpha * iv + (1 - alpha) * p)
          S(Some(size), Some(intv), 0)
        }
      }
      val rate = (s.size, s.interval) match {
        case (Some(z), Some(p)) if p > 0 => (1 - alpha / 2) * z / p
        case _                           => BigDecimal(0)
      }
      Vector.fill(horizon)(r(rate))
    }
  }

  // Holt-Winters: additive trend × multiplicative 12-month seasonality. Needs ≥ 24 months; below that it
  // degrades to the seasonal-naive shape (never fails, never peeks).
  final class SeasonalEts(alpha: BigDecimal, beta: BigDecimal, gamma: BigDecimal) extends DemandModel {
    val key            = "seasonal_ets"
    val version        = 1
    private val period = 12
    def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal] =
      if (h.qty.length < 2 * period) SeasonalNaive.predict(h, horizon)
      else {
        val init      = h.qty.take(period)
        val initLevel = mean(init).max(BigDecimal("0.0001"))
        val seas0     = init.map(x => (x / initLevel).max(BigDecimal("0.01")))
        final case class S(level: BigDecimal, trend: BigDecimal, seas: Vector[BigDecimal])
        val fitted = h.qty.zipWithIndex.drop(period).foldLeft(S(initLevel, BigDecimal(0), seas0)) {
          case (s, (x, t)) =>
            val sIdx  = t % period
            val seasF = s.seas(sIdx).max(BigDecimal("0.01"))
            val level = alpha * (x / seasF) + (1 - alpha) * (s.level + s.trend)
            val trend = beta * (level - s.level) + (1 - beta) * s.trend
            val seasN = (gamma * (x / level.max(BigDecimal("0.0001"))) + (1 - gamma) * seasF).max(BigDecimal("0.01"))
            S(level, trend, s.seas.updated(sIdx, seasN))
        }
        Vector.tabulate(horizon) { i =>
          val sIdx = (h.qty.length + i) % period
          r((fitted.level + fitted.trend * (i + 1)) * fitted.seas(sIdx))
        }
      }
  }

  // The depletion model (doc 26 §4 — the structural edge): the customer's shelf must empty before they reorder.
  // Cumulative expected sell-in by month m = max(0, velocity·m − shelf headroom); the monthly forecast is the
  // difference. With no telemetry it degrades to the trailing mean (never fails).
  object Depletion extends DemandModel {
    val key     = "depletion"
    val version = 1
    def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal] =
      (h.shelfStock, h.activationVelocity) match {
        case (Some(shelf), Some(v)) if v > 0 =>
          val cum = Vector.tabulate(horizon)(i => (v * (i + 1) - shelf).max(BigDecimal(0)))
          cum.zip(BigDecimal(0) +: cum).map { case (c, prev) => r(c - prev) }
        case _ => Vector.fill(horizon)(r(mean(h.qty.takeRight(6))))
      }
  }

  // Damped Holt linear trend (doc 26 §4 iteration 2): level + trend with damping φ — the standard answer to
  // "level models systematically under-forecast a growing business" without naive trend explosion at horizon.
  final class HoltDamped(alpha: BigDecimal, beta: BigDecimal, phi: BigDecimal, keyName: String = "holt_damped")
      extends DemandModel {
    val key     = keyName
    val version = 1
    def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal] =
      if (h.qty.length < 4) new Ewma(alpha).predict(h, horizon)
      else {
        final case class S(level: BigDecimal, trend: BigDecimal)
        val init = S(h.qty.head, (h.qty(1) - h.qty.head))
        val s = h.qty.drop(1).foldLeft(init) { (s, x) =>
          val level = alpha * x + (1 - alpha) * (s.level + phi * s.trend)
          val trend = beta * (level - s.level) + (1 - beta) * phi * s.trend
          S(level, trend)
        }
        Vector.tabulate(horizon) { i =>
          val dampSum = (1 to (i + 1)).map(k => phi.pow(k)).foldLeft(BigDecimal(0))(_ + _)
          r(s.level + dampSum * s.trend)
        }
      }
  }

  // Seasonal drift: same-month-last-year scaled by the trailing year-over-year growth ratio — seasonality AND
  // growth from two numbers, robust on short noisy series (capped ratio so one spike can't triple a forecast).
  object SeasonalDrift extends DemandModel {
    val key     = "seasonal_drift"
    val version = 1
    def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal] = {
      val base = SeasonalNaive.predict(h, horizon)
      if (h.qty.length < 18) base
      else {
        val recent = h.qty.takeRight(6).sum
        val prior  = h.qty.dropRight(12).takeRight(6).sum
        val ratio  = if (prior > 0) (recent / prior).min(BigDecimal(2)).max(BigDecimal("0.5")) else BigDecimal(1)
        base.map(x => r(x * ratio))
      }
    }
  }

  // Trailing 3-month run-rate — the lumpy-channel answer: recent level including any recent step-change,
  // immune to year-old history (large-PO channels re-base quickly).
  object RunRate3 extends DemandModel {
    val key     = "runrate3"
    val version = 1
    def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal] =
      Vector.fill(horizon)(r(mean(h.qty.takeRight(3))))
  }

  // The order-book structural model (doc 26 §4a — the user-specified PO calibration): the quarter ahead is the
  // open book at the origin × the conversion measured on PRE-origin cohorts, plus the created-and-won-in-quarter
  // run-rate. Incoming POs are near-certain near-term revenue — statistics can't see them, the book can. Without
  // a book it degrades to the run-rate (never fails, never peeks).
  object OrderBook extends DemandModel {
    val key     = "order_book"
    val version = 1
    def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal] =
      (h.openBook, h.bookConversion, h.newBusinessQ) match {
        case (Some(book), Some(conv), Some(newBiz)) =>
          Vector.fill(horizon)(r((book * conv + newBiz) / horizon))
        case _ => RunRate3.predict(h, horizon)
      }
  }

  // The retail funnel (doc 26 §4a — the user's decomposition): components measured singly from pre-origin
  // cohorts (created volume, conversion per payment channel, conversion-by-age), cumulated by the calc into
  // one quarter expectation. Without a pipeline it degrades to the run-rate (never fails, never peeks).
  object RetailFunnel extends DemandModel {
    val key     = "retail_funnel"
    val version = 1
    def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal] =
      h.funnelExpectedQ match {
        case Some(expected) => Vector.fill(horizon)(r(expected / horizon))
        case None           => RunRate3.predict(h, horizon)
      }
  }

  // The same funnel with the created-volume component at the LAST month's rate × 3 — the ramp-tracking
  // variant; a steeply growing channel outruns any quarter-trailing window.
  object RetailFunnelMomentum extends DemandModel {
    val key     = "retail_funnel_m"
    val version = 1
    def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal] =
      h.funnelMomentumQ match {
        case Some(expected) => Vector.fill(horizon)(r(expected / horizon))
        case None           => RunRate3.predict(h, horizon)
      }
  }

  // Pantry reversal (doc 26 §4a — depletion visible in sell-in alone): a customer who just bought far above
  // their consumption rate sits on a full shelf and under-orders next quarter (measured Q2'25: EVERY model
  // over-forecast EVERY top account after the Q1'25 stocking wave). Consumption = trailing-four-quarter mean;
  // next quarter = 2·consumption − lastQ, clamped to [0, 2·consumption]; beyond the reversal quarter the
  // account returns to its consumption rate.
  object PantryReversal extends DemandModel {
    val key     = "pantry_reversal"
    val version = 1
    def predict(h: DemandHistory, horizon: Int): Vector[BigDecimal] = {
      val quarters = h.qty.reverse.grouped(3).filter(_.size == 3).map(_.sum).toVector.reverse
      if (quarters.size < 2) RunRate3.predict(h, horizon)
      else {
        val consumption = mean(quarters.takeRight(4))
        val reversal    = (2 * consumption - quarters.last).max(BigDecimal(0)).min(2 * consumption)
        Vector.tabulate(horizon)(i => r(if (i < 3) reversal / 3 else consumption / 3))
      }
    }
  }

  // The registry (doc 26 §4) — code-defined; the loop ranks these mechanically, nothing is hand-picked.
  val registry: List[DemandModel] = List(
    SeasonalNaive,
    new Ewma(BigDecimal("0.3")),
    new CrostonSba(BigDecimal("0.2")),
    new SeasonalEts(BigDecimal("0.3"), BigDecimal("0.05"), BigDecimal("0.2")),
    new HoltDamped(BigDecimal("0.3"), BigDecimal("0.1"), BigDecimal("0.9")),
    new HoltDamped(BigDecimal("0.5"), BigDecimal("0.2"), BigDecimal("0.95"), "holt_fast"),
    RunRate3,
    SeasonalDrift,
    Depletion,
    OrderBook,
    RetailFunnel,
    RetailFunnelMomentum,
    PantryReversal
  )
}
