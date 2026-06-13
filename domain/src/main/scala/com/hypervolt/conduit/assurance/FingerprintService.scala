package com.hypervolt.conduit.assurance

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.util.transactor.Transactor

// Computes the reproducibility fingerprint over the id-independent aggregates of the money + forecast tables
// and records it as a reproduction_manifest row (doc 29 D). Two runs over the same data at the same ingest
// SHA produce the SAME digest; a drift (or non-determinism) yields a different digest under the same
// (scope, sha) — which CTRL-REPRO surfaces. The aggregates avoid ids entirely (count + numeric sum per key),
// so service-generated UUIDs do not perturb the digest — only the money does.
final class FingerprintService[F[_]: Async](xa: Transactor[F]) {

  // The ledger scope: every posted gl_entry netted per (account_key, side) — the trial-balance shape, the
  // thing that must reproduce. Plus the recognition + IC aggregates that the lifecycle laws conserve.
  private val lines: ConnectionIO[List[FingerprintLine]] =
    for {
      gl <-
        sql"""SELECT account_key || ':' || side, count(*), COALESCE(SUM(amount_minor), 0)
                  FROM gl_entry WHERE posted GROUP BY 1"""
          .query[(String, Long, BigDecimal)]
          .to[List]
      rr <-
        sql"""SELECT currency, count(*), COALESCE(SUM(revenue_ex_vat + vat), 0)
                  FROM revenue_recognition GROUP BY 1"""
          .query[(String, Long, BigDecimal)]
          .to[List]
      ic <-
        sql"""SELECT currency, count(*), COALESCE(SUM(uplift_total - returned_uplift), 0)
                  FROM ic_match WHERE reversed_at IS NULL GROUP BY 1"""
          .query[(String, Long, BigDecimal)]
          .to[List]
    } yield gl.map { case (k, c, s) => FingerprintLine("gl_entry", k, c, s) } ++
      rr.map { case (k, c, s) => FingerprintLine("revenue_recognition", k, c, s) } ++
      ic.map { case (k, c, s) => FingerprintLine("ic_match", k, c, s) }

  // Compute + record one manifest for (scope, ingestSha). Returns the digest.
  def record(scope: String, ingestSha: String): F[String] =
    lines
      .flatMap { ls =>
        val d = Fingerprint.digest(ingestSha, ls)
        sql"""INSERT INTO reproduction_manifest (scope, git_sha, digest, line_count)
            VALUES ($scope, $ingestSha, $d, ${ls.size.toLong})""".update.run.as(d)
      }
      .transact(xa)

  // The current digest without recording — for ad-hoc comparison / the scripting report.
  def compute(ingestSha: String): F[String] =
    lines.map(Fingerprint.digest(ingestSha, _)).transact(xa)
}
