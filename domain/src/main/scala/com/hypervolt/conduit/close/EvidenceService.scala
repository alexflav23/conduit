package com.hypervolt.conduit.close

import cats.effect.Async
import cats.syntax.all._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.security.MessageDigest
import java.util.UUID

// Evidence export (doc 14 §5.3 / doc 20 D21): a signed, time-stamped pack for the external auditor — the period's
// reconciliations + control runs assembled into one immutable bundle with a content hash, so it is tamper-evident
// and reproducible (re-running the query over the same rows yields the same `content_sha256`). WORM-style: a read,
// never an edit. The `generated_at` is passed in (deterministic content stays hash-stable; the stamp lives outside).
final class EvidenceService[F[_]: Async](xa: Transactor[F]) {

  def pack(periodId: UUID, generatedAt: String): F[Json] =
    (reconciliations(periodId), controlRuns(periodId)).tupled.transact(xa).map {
      case (recs, ctrls) =>
        val body = Json.obj(
          "period_id"       -> periodId.toString.asJson,
          "reconciliations" -> recs.asJson,
          "control_runs"    -> ctrls.asJson
        )
        body.deepMerge(
          Json.obj("generated_at" -> generatedAt.asJson, "content_sha256" -> sha256(body.noSpaces).asJson)
        )
    }

  private def sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private def reconciliations(periodId: UUID): doobie.ConnectionIO[List[Json]] =
    sql"""SELECT type, expected, actual, variance, currency, status, signed_off_by
          FROM reconciliation WHERE period_id = $periodId ORDER BY type"""
      .query[(String, BigDecimal, BigDecimal, BigDecimal, Option[String], String, Option[UUID])]
      .to[List]
      .map(_.map {
        case (t, e, a, v, ccy, st, signer) =>
          Json.obj(
            "type"          -> t.asJson,
            "expected"      -> e.asJson,
            "actual"        -> a.asJson,
            "variance"      -> v.asJson,
            "currency"      -> ccy.asJson,
            "status"        -> st.asJson,
            "signed_off_by" -> signer.map(_.toString).asJson
          )
      })

  private def controlRuns(periodId: UUID): doobie.ConnectionIO[List[Json]] =
    sql"""SELECT c.code, cr.result, cr.detail FROM control_run cr JOIN control c ON c.id = cr.control_id
          WHERE cr.period_id = $periodId ORDER BY c.code, cr.run_at"""
      .query[(String, String, Option[Json])]
      .to[List]
      .map(_.map {
        case (code, result, detail) =>
          Json.obj("control" -> code.asJson, "result" -> result.asJson, "detail" -> detail.getOrElse(Json.Null))
      })
}
