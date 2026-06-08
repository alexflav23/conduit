package com.hypervolt.conduit.tax

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import io.circe.syntax._
import java.time.LocalDate
import java.util.UUID

final case class NexusRow(
    id: UUID,
    thresholdAmount: Option[BigDecimal],
    thresholdTxnCount: Option[Int],
    salesToDate: BigDecimal,
    txnCountToDate: Int,
    status: String
)

// Persistence for the determination subsystem (doc 16 §2.4/§2.5/§2.6): provider routing, the immutable
// append-only tax_quote + its lines, supersession (preview → placed → invoice), and the US/CA nexus rolling totals.
object TaxQuoteRepo {

  // Most specific active routing row for (jurisdiction, tax_type) at as_of — the only place provider selection lives.
  def provider(jurisdiction: String, taxType: String, asOf: LocalDate): ConnectionIO[String] =
    sql"""SELECT provider FROM tax_routing
          WHERE status = 'active'
            AND (jurisdiction = $jurisdiction OR jurisdiction IS NULL)
            AND (tax_type = $taxType OR tax_type IS NULL)
            AND effective_from <= $asOf AND (effective_to IS NULL OR effective_to > $asOf)
          ORDER BY (jurisdiction IS NOT NULL) DESC, priority ASC
          LIMIT 1"""
      .query[String]
      .option
      .map(_.getOrElse("rate_table"))

  def insertQuote(id: UUID, req: TaxQuoteRequest, resp: TaxQuoteResponse, providerName: String): ConnectionIO[Int] =
    sql"""INSERT INTO tax_quote
            (id, context, order_id, tranche_id, order_invoice_id, intercompany_link_id, entity_id,
             ship_from_jurisdiction, ship_from_region, ship_to_jurisdiction, ship_to_region, ship_to_postcode,
             party_tax_status, buyer_tax_id, supply_kind, provider, provider_ref, provider_version, currency,
             total_tax, reverse_charge, rounding_policy, rates_asof, request_snapshot, response_snapshot)
          VALUES
            ($id, ${req.context}, ${req.orderId}, ${req.trancheId}, ${req.orderInvoiceId}, ${req.intercompanyLinkId},
             ${req.entityId}, ${req.shipFrom.jurisdiction}, ${req.shipFrom.region}, ${req.shipTo.jurisdiction},
             ${req.shipTo.region}, ${req.shipTo.postcode}, ${req.partyTaxStatus}, ${req.buyerTaxId}, ${resp.supplyKind},
             $providerName, ${resp.determinationRef}, ${resp.providerVersion}, ${resp.currency}, ${resp.taxTotal},
             ${resp.reverseCharge}, ${resp.roundingPolicy}, ${resp.ratesAsof}, ${req.asJson}, ${resp.asJson})""".update.run

  def insertLines(quoteId: UUID, resp: TaxQuoteResponse): ConnectionIO[Int] =
    resp.lines
      .traverse { l =>
        sql"""INSERT INTO tax_quote_line
              (tax_quote_id, product_variant_id, line_ref, hs_code, qty, taxable_amount, line_tax_total,
               effective_rate_pct, reverse_charge, regime_code, components)
            VALUES ($quoteId, ${l.productVariantId}, ${l.ref}, ${Option.empty[String]}, 0, ${l.taxableAmount},
               ${l.lineTaxTotal}, ${l.effectiveRatePct}, ${l.reverseCharge}, ${l.regimeCode}, ${l.components.asJson})""".update.run
      }
      .map(_.sum)

  // Append-only versioning: point the prior non-superseded quote for this (key, context) at the new one.
  def supersedePrior(req: TaxQuoteRequest, newId: UUID): ConnectionIO[Int] =
    req.orderId
      .map(o => sql"""UPDATE tax_quote SET superseded_by = $newId
              WHERE order_id = $o AND context = ${req.context} AND id <> $newId AND superseded_by IS NULL""".update.run)
      .orElse(
        req.intercompanyLinkId.map(ic =>
          sql"""UPDATE tax_quote SET superseded_by = $newId
                WHERE intercompany_link_id = $ic AND context = ${req.context} AND id <> $newId AND superseded_by IS NULL""".update.run
        )
      )
      .getOrElse(0.pure[ConnectionIO])

  def nexus(entityId: UUID, jurisdiction: String, region: String): ConnectionIO[Option[NexusRow]] =
    sql"""SELECT id, threshold_amount, threshold_txn_count, sales_to_date, txn_count_to_date, status
          FROM nexus_profile
          WHERE entity_id = $entityId AND jurisdiction = $jurisdiction AND region = $region"""
      .query[NexusRow]
      .option

  def advanceNexus(id: UUID, addSales: BigDecimal, status: String, crossed: Boolean): ConnectionIO[Int] =
    sql"""UPDATE nexus_profile
          SET sales_to_date = sales_to_date + $addSales,
              txn_count_to_date = txn_count_to_date + 1,
              status = $status,
              crossed_at = CASE WHEN $crossed AND crossed_at IS NULL THEN now() ELSE crossed_at END,
              updated_at = now()
          WHERE id = $id""".update.run

  // Replay support (the reproducibility control, doc 16 §8): the stored request snapshot + the provider used.
  def requestSnapshot(quoteId: UUID): ConnectionIO[Option[(io.circe.Json, String, LocalDate)]] =
    sql"SELECT request_snapshot, provider, rates_asof FROM tax_quote WHERE id = $quoteId"
      .query[(io.circe.Json, String, LocalDate)]
      .option
}
