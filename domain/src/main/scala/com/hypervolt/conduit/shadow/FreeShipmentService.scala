package com.hypervolt.conduit.shadow

import cats.effect.Async
import cats.syntax.all._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.util.UUID

// Classifies the free-shipment population (COGS-without-revenue, wholly-free orders) by inferred reason so it can
// be trended, forecast off sales, and turned into a warranty accrual. Categories are derived from transparent,
// source-backed signals — recipient is an existing paying customer (replacement), never paid (prospect sample),
// converted later (sample that worked), internal/marketplace by name. A projection: rebuilt idempotently, human
// overrides preserved. This is NOT invented data — every row carries the rule that classified it.
final class FreeShipmentService[F[_]: Async](xa: Transactor[F]) {

  // The category + its plain-language basis, as one CASE reused by the rebuild.
  // Internal/engineering accounts (Hypervolt Engineering, test, DONOTUSE placeholders) = R&D cost, units built for
  // engineering/prototyping/testing — NOT samples. Amazon = marketplace returns/replacements — never samples.
  private val internalMatch =
    fr"(p.display_name ILIKE 'hypervolt%' OR p.display_name ILIKE '%engineering%' OR p.display_name ILIKE '%donotuse%' OR p.display_name ILIKE '%test%' OR p.display_name ILIKE '% r&d%')"

  private val categoryExpr =
    fr"""CASE
           WHEN """ ++ internalMatch ++ fr""" THEN 'r_and_d'
           WHEN p.display_name ILIKE 'amazon%' THEN 'marketplace_return'
           WHEN EXISTS (SELECT 1 FROM "order" o2 WHERE o2.sold_to_party_id = o.sold_to_party_id
                         AND o2.subtotal_ex_vat > 0 AND o2.order_date <= o.order_date) THEN 'free_replacement_to_customer'
           WHEN EXISTS (SELECT 1 FROM "order" o2 WHERE o2.sold_to_party_id = o.sold_to_party_id
                         AND o2.subtotal_ex_vat > 0) THEN 'sample_converted'
           ELSE 'sample_prospect'
         END"""

  private val basisExpr =
    fr"""CASE
           WHEN """ ++ internalMatch ++ fr""" THEN 'internal/engineering account — units built for R&D, prototyping or testing'
           WHEN p.display_name ILIKE 'amazon%' THEN 'Amazon marketplace returns/replacements (not a sample)'
           WHEN EXISTS (SELECT 1 FROM "order" o2 WHERE o2.sold_to_party_id = o.sold_to_party_id
                         AND o2.subtotal_ex_vat > 0 AND o2.order_date <= o.order_date) THEN 'free unit to a customer with a prior paid order — warranty/RMA/goodwill/promo, reason pending RMA-ticket linkage'
           WHEN EXISTS (SELECT 1 FROM "order" o2 WHERE o2.sold_to_party_id = o.sold_to_party_id
                         AND o2.subtotal_ex_vat > 0) THEN 'free unit preceded the customer''s first paid order (sample that converted)'
           ELSE 'free unit to a party that never placed a paid order (prospect sample)'
         END"""

  // Full refresh from recognition + order + party. Human overrides (override_by set) keep their category.
  private val insertClassified =
    (fr"""INSERT INTO free_shipment (dispatch_id, order_id, party_id, party_name, entity_id, category, basis, cogs, currency, occurred_at)
          SELECT rr.dispatch_id, rr.order_id, o.sold_to_party_id, p.display_name, rr.entity_id, """
      ++ categoryExpr ++ fr"," ++ basisExpr ++ fr""", rr.cogs, rr.currency, rr.recognized_at
          FROM revenue_recognition rr JOIN "order" o ON o.id = rr.order_id
            LEFT JOIN party p ON p.id = o.sold_to_party_id
          WHERE rr.cogs > 0 AND rr.revenue_ex_vat = 0 AND o.subtotal_ex_vat = 0
          ON CONFLICT (dispatch_id) DO UPDATE SET
            category = CASE WHEN free_shipment.override_by IS NOT NULL THEN free_shipment.category ELSE EXCLUDED.category END,
            basis = CASE WHEN free_shipment.override_by IS NOT NULL THEN free_shipment.basis ELSE EXCLUDED.basis END,
            cogs = EXCLUDED.cogs, currency = EXCLUDED.currency, occurred_at = EXCLUDED.occurred_at,
            party_name = EXCLUDED.party_name""").update.run

  def rebuild: F[Int] =
    (insertClassified *>
      sql"""DELETE FROM free_shipment fs WHERE NOT EXISTS (
              SELECT 1 FROM revenue_recognition rr JOIN "order" o ON o.id = rr.order_id
              WHERE rr.dispatch_id = fs.dispatch_id AND rr.cogs > 0 AND rr.revenue_ex_vat = 0 AND o.subtotal_ex_vat = 0)""".update.run *>
      // Confirm warranty against the HubSpot RMA tickets: a free unit whose serial IS an RMA replacement serial is a
      // CONFIRMED warranty/RMA replacement (not a heuristic free_replacement_to_customer). Human overrides preserved.
      sql"""UPDATE free_shipment fs SET category = 'warranty_replacement_confirmed',
              basis = 'free unit serial matches a HubSpot RMA replacement (rma_serial_number)'
            WHERE fs.override_by IS NULL AND EXISTS (
              SELECT 1 FROM serial_unit s JOIN rma_ticket t
                ON lower(regexp_replace(t.replacement_serial, '[^0-9A-Za-z]', '', 'g')) = s.serial_no
              WHERE s.dispatch_id = fs.dispatch_id)""".update.run *>
      sql"SELECT count(*) FROM free_shipment".query[Int].unique).transact(xa)

  def summary: F[List[Json]] =
    sql"""SELECT category, count(*), round(sum(cogs),2), round(avg(cogs),2)
          FROM free_shipment GROUP BY category ORDER BY sum(cogs) DESC"""
      .query[(String, Int, BigDecimal, BigDecimal)]
      .to[List]
      .transact(xa)
      .map(_.map {
        case (cat, n, cogs, avg) =>
          Json.obj(
            "category"      -> cat.asJson,
            "shipments"     -> n.asJson,
            "cogs_absorbed" -> cogs.asJson,
            "avg_cogs"      -> avg.asJson
          )
      })

  def trend: F[List[Json]] =
    sql"""SELECT to_char(date_trunc('month', occurred_at), 'YYYY-MM') AS m, category, count(*), round(sum(cogs),2)
          FROM free_shipment WHERE occurred_at IS NOT NULL GROUP BY 1, 2 ORDER BY 1, 2"""
      .query[(String, String, Int, BigDecimal)]
      .to[List]
      .transact(xa)
      .map(_.map {
        case (month, cat, n, cogs) =>
          Json.obj("month" -> month.asJson, "category" -> cat.asJson, "shipments" -> n.asJson, "cogs" -> cogs.asJson)
      })

  // Cumulative (lifetime-to-date) free-replacement counts. NOT a warranty rate — it conflates warranty/RMA/goodwill/
  // promo and divides lifelong replacements by paid-units-to-date. A real warranty rate is a per-serial lifecycle
  // measure (activation cohort × elapsed-warranty-years, confirmed warranty replacements only), which needs the
  // RMA-ticket linkage (which unit replaced which). This endpoint reports the raw cumulative basis only.
  def replacementMetrics: F[Json] =
    (
      sql"SELECT count(*), round(COALESCE(sum(cogs),0),2) FROM free_shipment WHERE category = 'free_replacement_to_customer'"
        .query[(Int, BigDecimal)]
        .unique,
      sql"SELECT count(*) FROM revenue_recognition WHERE revenue_ex_vat > 0".query[Int].unique
    ).tupled.transact(xa).map {
      case ((replacements, cogs), paidUnits) =>
        Json.obj(
          "free_replacements_to_customers" -> replacements.asJson,
          "replacement_cogs"               -> cogs.asJson,
          "paid_units_to_date"             -> paidUnits.asJson,
          "avg_replacement_cost" -> (if (replacements > 0)
                                       (cogs / replacements).setScale(2, BigDecimal.RoundingMode.HALF_UP)
                                     else BigDecimal(0)).asJson,
          "caveat" -> "cumulative lifetime-to-date; not a warranty rate — needs per-serial lifecycle + RMA-ticket linkage".asJson
        )
    }

  def reclassify(dispatchId: UUID, category: String, actor: UUID): F[Int] =
    sql"""UPDATE free_shipment SET category = $category, basis = 'manual override', override_by = $actor, override_at = now()
          WHERE dispatch_id = $dispatchId""".update.run.transact(xa)
}
