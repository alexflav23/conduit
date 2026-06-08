package com.hypervolt.conduit.tax

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.util.UUID

// The per-jurisdiction VAT exposure (doc 16 §1.3) as a reproducible projection over immutable rows: recognised VAT
// accrues, reversals (voids/cancellations/returns) reduce it, remittances deplete it. outstanding == what is still
// owed to each tax authority, per entity × jurisdiction × period. Σ outstanding for an entity ties to the
// VAT:<entity> ledger balance (the immutable proof).
object VatExposureRepo {

  def exposure(entityId: Option[UUID], jurisdiction: Option[String]): ConnectionIO[List[Json]] =
    sql"""
      WITH accrued AS (
        SELECT entity_id, vat_jurisdiction AS jur, to_char(recognized_at, 'YYYY-MM') AS period,
               SUM(vat) AS amt, 0::numeric AS rev, 0::numeric AS rem
        FROM revenue_recognition
        WHERE entity_id IS NOT NULL AND vat_jurisdiction IS NOT NULL
        GROUP BY entity_id, vat_jurisdiction, to_char(recognized_at, 'YYYY-MM')
      ),
      reversed AS (
        SELECT rr.entity_id, rr.vat_jurisdiction AS jur, to_char(rr.recognized_at, 'YYYY-MM') AS period,
               0::numeric AS amt, SUM(ivr.reversed_vat) AS rev, 0::numeric AS rem
        FROM invoice_reversal ivr JOIN revenue_recognition rr ON rr.dispatch_id = ivr.dispatch_id
        WHERE rr.entity_id IS NOT NULL AND rr.vat_jurisdiction IS NOT NULL
        GROUP BY rr.entity_id, rr.vat_jurisdiction, to_char(rr.recognized_at, 'YYYY-MM')
      ),
      remitted AS (
        SELECT entity_id, jurisdiction AS jur, period_key AS period,
               0::numeric AS amt, 0::numeric AS rev, SUM(amount) AS rem
        FROM vat_remittance GROUP BY entity_id, jurisdiction, period_key
      ),
      unioned AS (SELECT * FROM accrued UNION ALL SELECT * FROM reversed UNION ALL SELECT * FROM remitted)
      SELECT entity_id, jur, period, SUM(amt), SUM(rev), SUM(rem)
      FROM unioned
      WHERE ($entityId IS NULL OR entity_id = $entityId) AND ($jurisdiction IS NULL OR jur = $jurisdiction)
      GROUP BY entity_id, jur, period
      ORDER BY entity_id, jur, period
    """
      .query[(UUID, String, String, BigDecimal, BigDecimal, BigDecimal)]
      .to[List]
      .map(_.map {
        case (e, jur, period, accrued, reversed, remitted) =>
          Json.obj(
            "entity_id"    -> e.toString.asJson,
            "jurisdiction" -> jur.asJson,
            "period"       -> period.asJson,
            "accrued"      -> accrued.asJson,
            "reversed"     -> reversed.asJson,
            "remitted"     -> remitted.asJson,
            "outstanding"  -> (accrued - reversed - remitted).asJson
          )
      })

  // Σ outstanding across all jurisdictions/periods for an entity — what the VAT:<entity> ledger balance must equal.
  def outstandingForEntity(entityId: UUID): ConnectionIO[BigDecimal] =
    exposure(Some(entityId), None).map(
      _.foldLeft(BigDecimal(0))((acc, j) => acc + j.hcursor.get[BigDecimal]("outstanding").getOrElse(BigDecimal(0)))
    )
}
