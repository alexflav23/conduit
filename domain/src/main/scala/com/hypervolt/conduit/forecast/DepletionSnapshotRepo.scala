package com.hypervolt.conduit.forecast

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.LocalDate

// Captures the censored depletion state (shelf + activation velocity) for one origin, set-based over every
// (company, variant) with shelf/velocity as-of that origin — the same definition as BacktestEngine.depletionContext,
// run for the whole population at once. Idempotent (ON CONFLICT DO NOTHING): a snapshot of an origin is taken once
// and never rewritten, so the history a run-to-run rate delta reads is immutable and reproducible (doc 35 §4.1).
object DepletionSnapshotRepo {

  def snapshot(origin: LocalDate, source: String): ConnectionIO[Int] =
    sql"""INSERT INTO depletion_snapshot
            (origin_month, company_id, product_variant_id, shelf_stock, velocity_ewma, velocity_3m, runway_days, source)
          SELECT $origin, x.company_id, x.product_variant_id, x.shelf, x.vel6, x.vel3,
                 CASE WHEN x.vel6 > 0 THEN round(x.shelf / x.vel6 * 30, 1) ELSE NULL END,
                 $source
          FROM (
            SELECT su.company_id, su.product_variant_id,
              COUNT(*) FILTER (WHERE COALESCE(d.delivered_at, d.date::timestamptz) < $origin
                                 AND (su.activated_at IS NULL OR su.activated_at >= $origin))::numeric AS shelf,
              COUNT(*) FILTER (WHERE su.activated_at >= ${origin
      .minusMonths(6)} AND su.activated_at < $origin)::numeric / 6.0 AS vel6,
              COUNT(*) FILTER (WHERE su.activated_at >= ${origin
      .minusMonths(3)} AND su.activated_at < $origin)::numeric / 3.0 AS vel3
            FROM serial_unit su JOIN dispatch d ON d.id = su.dispatch_id
            WHERE su.company_id IS NOT NULL AND su.product_variant_id IS NOT NULL
            GROUP BY su.company_id, su.product_variant_id
          ) x
          WHERE x.shelf > 0 OR x.vel6 > 0
          ON CONFLICT (origin_month, company_id, product_variant_id) DO NOTHING""".update.run
}
