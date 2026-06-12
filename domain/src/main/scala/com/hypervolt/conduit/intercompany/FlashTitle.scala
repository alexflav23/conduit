package com.hypervolt.conduit.intercompany

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.util.UUID

// Flash title at dispatch (spec doc 28 §2.2): when the selling entity has a procurement parent (the
// principal), the customer dispatch prices an internal hop — the operating entity's TRUE cost is the
// TRANSFER price, not the landed cost. Represented as the UPLIFT pair on top of the physical COGS relief
// (single-source inventory: the warehouse posting is untouched):
//   operating:  DR COGS:op            / CR IC_AP:op->principal   (transfer - landed)
//   principal:  DR IC_AR:principal:op / CR IC_MARGIN:principal   (transfer - landed)
// Operating COGS totals to the transfer price; the principal books exactly the markup; the pair eliminates
// at group. Pricing: catalogue line per variant (doc 28 §2.1) -> policy formula -> FAIL CLOSED.
object FlashTitle {

  final case class FlashCtx(
      procurementEntity: UUID,
      market: UUID,
      priceListId: Option[UUID],
      transferTotal: BigDecimal,
      source: String // 'catalogue' | policy method code
  )

  // Resolve the dispatch's transfer total as-of the recognition date. Catalogue prices every variant or the
  // whole dispatch falls back to the (from=principal, to=operating) policy; no price anywhere -> Left.
  def resolve(
      dispatchId: UUID,
      operatingEntity: UUID,
      procurementEntity: UUID,
      market: UUID,
      landedTotal: BigDecimal,
      revenueExVat: BigDecimal,
      qty: Int,
      asOf: java.time.LocalDate
  ): ConnectionIO[Either[String, FlashCtx]] =
    variantQtys(dispatchId).flatMap { lines =>
      lines
        .traverse {
          case (variant, q) =>
            ProcurementCatalogue
              .resolve(procurementEntity, market, variant, asOf)
              .map(_.map {
                case (listId, price, _) => (listId, price * q)
              })
        }
        .flatMap { resolved =>
          if (resolved.nonEmpty && resolved.forall(_.isDefined)) {
            val hits = resolved.flatten
            FlashCtx(procurementEntity, market, hits.headOption.map(_._1), hits.map(_._2).sum, "catalogue")
              .asRight[String]
              .pure[ConnectionIO]
          } else policyFallback(operatingEntity, procurementEntity, market, landedTotal, revenueExVat, qty, asOf)
        }
    }

  private def policyFallback(
      operatingEntity: UUID,
      procurementEntity: UUID,
      market: UUID,
      landedTotal: BigDecimal,
      revenueExVat: BigDecimal,
      qty: Int,
      asOf: java.time.LocalDate
  ): ConnectionIO[Either[String, FlashCtx]] =
    sql"""SELECT method, markup_pct, resale_margin_pct, fixed_price
          FROM transfer_price_policy
          WHERE from_entity_id = $procurementEntity AND to_entity_id = $operatingEntity AND status = 'active'
            AND effective_from::date <= $asOf AND (effective_to IS NULL OR effective_to::date >= $asOf)
          ORDER BY effective_from DESC LIMIT 1"""
      .query[(String, Option[BigDecimal], Option[BigDecimal], Option[BigDecimal])]
      .option
      .map {
        case None =>
          "no catalogue line and no transfer-price policy for this hop — an unpriced internal sale fails closed (doc 28 §2.1)"
            .asLeft[FlashCtx]
        case Some((method, markup, resale, fixed)) =>
          TransferPricing.Method
            .fromCode(method)
            .toRight(s"unknown TP method $method")
            .flatMap {
              case TransferPricing.Method.CostPlus =>
                markup.toRight("cost_plus policy missing markup_pct").map(m => landedTotal * (1 + m / 100))
              case TransferPricing.Method.ResaleMinus =>
                resale.toRight("resale_minus policy missing resale_margin_pct").map(m => revenueExVat * (1 - m / 100))
              case TransferPricing.Method.Fixed =>
                fixed.toRight("fixed policy missing fixed_price").map(_ * qty)
            }
            .map(total => FlashCtx(procurementEntity, market, None, total, method))
      }

  def recordMatch(
      dispatchId: UUID,
      orderId: UUID,
      operatingEntity: UUID,
      flash: FlashCtx,
      currency: String,
      landedTotal: BigDecimal,
      opLegId: BigInt,
      prLegId: BigInt
  ): ConnectionIO[Int] =
    originBatches(dispatchId).flatMap(batches => sql"""INSERT INTO ic_match
              (dispatch_id, order_id, operating_entity_id, procurement_entity_id, price_list_id, currency,
               landed_total, transfer_total, uplift_total, origin_batch_ids, op_leg_tb_transfer_id, pr_leg_tb_transfer_id)
            VALUES ($dispatchId, $orderId, $operatingEntity, ${flash.procurementEntity}, ${flash.priceListId},
                    $currency, $landedTotal, ${flash.transferTotal}, ${flash.transferTotal - landedTotal},
                    $batches, ${BigDecimal(opLegId)}, ${BigDecimal(prLegId)})
            ON CONFLICT (dispatch_id) DO NOTHING""".update.run)

  private def variantQtys(dispatchId: UUID): ConnectionIO[List[(UUID, Int)]] =
    sql"""SELECT ol.product_variant_id, SUM(dl.qty)::int
          FROM dispatch_line dl JOIN order_line ol ON ol.id = dl.order_line_id
          WHERE dl.dispatch_id = $dispatchId GROUP BY 1"""
      .query[(UUID, Int)]
      .to[List]

  // The physical genealogy: the dispatched serials' batches (-> GRN -> the CM's PO via lot_batch).
  private def originBatches(dispatchId: UUID): ConnectionIO[List[UUID]] =
    sql"""SELECT DISTINCT lot_batch_id FROM serial_unit
          WHERE dispatch_id = $dispatchId AND lot_batch_id IS NOT NULL"""
      .query[UUID]
      .to[List]

  // ----- cancellations & alterations (doc 28 §2.5): the genealogy survives the unwind -----

  final case class MatchRow(
      operatingEntity: UUID,
      procurementEntity: UUID,
      landedTotal: BigDecimal,
      upliftTotal: BigDecimal,
      reversed: Boolean
  )

  def matchForDispatch(dispatchId: UUID): ConnectionIO[Option[MatchRow]] =
    sql"""SELECT operating_entity_id, procurement_entity_id, landed_total, uplift_total, reversed_at IS NOT NULL
          FROM ic_match WHERE dispatch_id = $dispatchId"""
      .query[MatchRow]
      .option

  // Full void: stamp the reversal on the match (append-only row, columns only ever NULL -> value once).
  def stampReversal(dispatchId: UUID, reversalId: UUID, opLeg: BigInt, prLeg: BigInt): ConnectionIO[Int] =
    sql"""UPDATE ic_match
          SET reversed_at = now(), reversal_id = $reversalId,
              rev_op_leg_tb_transfer_id = ${BigDecimal(opLeg)}, rev_pr_leg_tb_transfer_id = ${BigDecimal(prLeg)}
          WHERE dispatch_id = $dispatchId AND reversed_at IS NULL""".update.run

  // Partial return: the per-unit uplift share for a serial's dispatch (uniform over the dispatched units),
  // accumulated on the match so CTRL-IC-MATCH can prove Σ(unwound) <= uplift_total.
  def upliftShareForSerial(serialUnitId: UUID): ConnectionIO[Option[(UUID, UUID, UUID, BigDecimal)]] =
    sql"""SELECT m.dispatch_id, m.operating_entity_id, m.procurement_entity_id,
                 (m.uplift_total / NULLIF((SELECT SUM(dl.qty) FROM dispatch_line dl WHERE dl.dispatch_id = m.dispatch_id), 0))
          FROM serial_unit su JOIN ic_match m ON m.dispatch_id = su.dispatch_id
          WHERE su.id = $serialUnitId AND m.reversed_at IS NULL"""
      .query[(UUID, UUID, UUID, Option[BigDecimal])]
      .option
      .map(_.flatMap { case (d, op, pr, share) => share.map(s => (d, op, pr, s)) })

  def accumulateReturnedUplift(dispatchId: UUID, share: BigDecimal): ConnectionIO[Int] =
    sql"""UPDATE ic_match SET returned_uplift = returned_uplift + $share
          WHERE dispatch_id = $dispatchId""".update.run
}
