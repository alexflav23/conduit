package com.hypervolt.conduit.order

import com.hypervolt.conduit.pricing.QuoteLineResult
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

final case class CreditProfileRow(creditLimit: BigDecimal, currency: String, policy: String)

final case class OrderHeaderRow(
    status: String,
    dispatchedAt: Option[Instant],
    amendCutoff: Option[Instant],
    channelId: UUID,
    marketId: UUID,
    entityId: Option[UUID],
    currency: String,
    soldTo: UUID,
    billTo: UUID,
    paymentMethod: String
)

object OrderRepo {

  def insertOrder(
      in: PlaceOrderInput,
      status: String,
      adlpCategory: String,
      subtotal: BigDecimal,
      vat: BigDecimal,
      total: BigDecimal,
      amendCutoff: Option[Instant]
  ): ConnectionIO[(UUID, String)] =
    sql"""INSERT INTO "order"
            (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, customer_po_number, channel_id, market_id,
             status, adlp_category, txn_currency, subtotal_ex_vat, vat_total, total_inc_vat, payment_method,
             requested_delivery, amend_cutoff, created_by, source_attachment_id)
          VALUES ('ORD-' || nextval('order_no_seq'), ${in.orderType}, ${in.entityId}, ${in.soldToPartyId},
             ${in.billToPartyId}, ${in.customerPoNumber}, ${in.channelId}, ${in.marketId}, $status, $adlpCategory,
             ${in.currency}, $subtotal, $vat, $total, ${in.paymentMethod}, ${in.requestedDelivery}, $amendCutoff,
             ${in.createdBy}, ${in.sourceAttachmentId})
          RETURNING id, order_no""".query[(UUID, String)].unique

  def insertLine(orderId: UUID, variantId: UUID, priced: QuoteLineResult, isScheduled: Boolean): ConnectionIO[UUID] =
    sql"""INSERT INTO order_line
            (order_id, product_variant_id, qty, unit_price_ex_vat, discount_pct, tax_regime, vat_amount,
             line_total_inc_vat, price_rule_id, price_agreement_id, adlp_category, is_scheduled, status)
          VALUES ($orderId, $variantId, ${priced.qty}, ${priced.unitPriceExVat}, ${priced.appliedDiscountPct},
             NULL, ${priced.vat}, ${priced.lineTotalIncVat}, ${priced.priceRuleId}, ${priced.priceAgreementId},
             ${priced.adlpCategory}, $isScheduled, 'open')
          RETURNING id""".query[UUID].unique

  def insertTranche(lineId: UUID, seq: Int, qty: Int, requestedDate: LocalDate): ConnectionIO[Int] =
    sql"""INSERT INTO delivery_tranche (order_line_id, seq, qty, requested_date, status)
          VALUES ($lineId, $seq, $qty, $requestedDate, 'scheduled')""".update.run

  // The desk workflow artifact for a held tier request (doc 24 §6.3): order-level, linked to the draft agreement
  // it waits on. No requested price — there are no typed prices; the price IS the requested tier ladder.
  def insertTierRequestException(orderId: UUID, agreementId: UUID): ConnectionIO[Int] =
    sql"""INSERT INTO adlp_exception (order_id, agreement_id, status,
            party_id, justification)
          SELECT $orderId, $agreementId, 'pending_ceo', o.sold_to_party_id, pa.justification
          FROM "order" o, price_agreement pa WHERE o.id = $orderId AND pa.id = $agreementId""".update.run

  def heldOrderIds(agreementId: UUID): ConnectionIO[List[UUID]] =
    sql"""SELECT DISTINCT e.order_id FROM adlp_exception e JOIN "order" o ON o.id = e.order_id
          WHERE e.agreement_id = $agreementId AND o.status = 'pending_ceo'"""
      .query[UUID]
      .to[List]

  // The order's lines as re-quotable inputs (sku + qty; the price comes from resolution, never from the row).
  def lineInputs(orderId: UUID): ConnectionIO[List[PlaceLineInput]] =
    sql"""SELECT pv.sku, ol.qty FROM order_line ol JOIN product_variant pv ON pv.id = ol.product_variant_id
          WHERE ol.order_id = $orderId"""
      .query[(String, Int)]
      .to[List]
      .map(_.map { case (sku, qty) => PlaceLineInput(sku, qty, None, Nil) })

  def approveTierRequestException(orderId: UUID, agreementId: UUID, approver: UUID): ConnectionIO[Int] =
    sql"""UPDATE adlp_exception SET status = 'approved', approved_by = $approver, decided_at = now()
          WHERE order_id = $orderId AND agreement_id = $agreementId AND status = 'pending_ceo'""".update.run

  def creditProfile(partyId: UUID): ConnectionIO[Option[CreditProfileRow]] =
    sql"SELECT credit_limit, currency, policy FROM credit_profile WHERE party_id = $partyId"
      .query[CreditProfileRow]
      .option

  def openOrdersTotal(partyId: UUID): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(total_inc_vat), 0) FROM "order"
          WHERE bill_to_party_id = $partyId AND status NOT IN ('cancelled')"""
      .query[BigDecimal]
      .unique

  def header(orderId: UUID): ConnectionIO[Option[OrderHeaderRow]] =
    sql"""SELECT status, dispatched_at, amend_cutoff, channel_id, market_id, entity_id, txn_currency,
                 sold_to_party_id, bill_to_party_id, payment_method
          FROM "order" WHERE id = $orderId""".query[OrderHeaderRow].option

  def snapshotLines(orderId: UUID): ConnectionIO[Json] =
    sql"SELECT product_variant_id, qty, unit_price_ex_vat, adlp_category FROM order_line WHERE order_id = $orderId"
      .query[(UUID, Int, BigDecimal, String)]
      .to[List]
      .map(rows =>
        Json.fromValues(rows.map {
          case (variant, qty, price, adlp) =>
            Json.obj(
              "variant"           -> variant.toString.asJson,
              "qty"               -> qty.asJson,
              "unit_price_ex_vat" -> price.toString.asJson,
              "adlp"              -> adlp.asJson
            )
        })
      )

  def tranchesCount(orderId: UUID): ConnectionIO[Int] =
    sql"""SELECT count(*) FROM delivery_tranche dt
          JOIN order_line ol ON ol.id = dt.order_line_id WHERE ol.order_id = $orderId""".query[Int].unique

  def lineCount(orderId: UUID): ConnectionIO[Int] =
    sql"SELECT count(*) FROM order_line WHERE order_id = $orderId".query[Int].unique

  def deleteLines(orderId: UUID): ConnectionIO[Int] =
    sql"DELETE FROM order_line WHERE order_id = $orderId".update.run

  def updateTotalsAndStatus(
      orderId: UUID,
      status: String,
      adlpCategory: String,
      subtotal: BigDecimal,
      vat: BigDecimal,
      total: BigDecimal
  ): ConnectionIO[Int] =
    sql"""UPDATE "order" SET status = $status, adlp_category = $adlpCategory, subtotal_ex_vat = $subtotal,
            vat_total = $vat, total_inc_vat = $total, updated_at = now() WHERE id = $orderId""".update.run

  def insertAmendment(
      orderId: UUID,
      actor: Option[UUID],
      before: io.circe.Json,
      after: io.circe.Json,
      reason: Option[String]
  ): ConnectionIO[Int] = {
    import doobie.postgres.circe.jsonb.implicits._
    sql"""INSERT INTO order_amendment (order_id, actor_user_id, before, after, reason)
          VALUES ($orderId, $actor, $before, $after, $reason)""".update.run
  }

  // (entity, market, channel, created_by) for authorising reads against the order's own scope.
  def scopeRow(orderId: UUID): ConnectionIO[Option[(Option[UUID], Option[UUID], Option[UUID], Option[UUID])]] =
    sql"""SELECT entity_id, market_id, channel_id, created_by FROM "order" WHERE id = $orderId"""
      .query[(Option[UUID], Option[UUID], Option[UUID], Option[UUID])]
      .option

  def viewJson(orderId: UUID): ConnectionIO[Option[Json]] =
    for {
      hdr <-
        sql"""SELECT order_no, status, adlp_category, subtotal_ex_vat, vat_total, total_inc_vat
                   FROM "order" WHERE id = $orderId"""
          .query[(String, String, String, BigDecimal, BigDecimal, BigDecimal)]
          .option
      lines <-
        sql"""SELECT id, product_variant_id, qty, unit_price_ex_vat, adlp_category, is_scheduled, status
                     FROM order_line WHERE order_id = $orderId"""
          .query[(UUID, UUID, Int, BigDecimal, String, Boolean, String)]
          .to[List]
      tranches <-
        sql"""SELECT dt.order_line_id, dt.seq, dt.qty, dt.status FROM delivery_tranche dt
                        JOIN order_line ol ON ol.id = dt.order_line_id WHERE ol.order_id = $orderId"""
          .query[(UUID, Int, Int, String)]
          .to[List]
    } yield hdr.map {
      case (no, st, adlp, sub, vat, tot) =>
        val byLine = tranches.groupBy(_._1)
        Json.obj(
          "id"              -> orderId.toString.asJson,
          "order_no"        -> no.asJson,
          "status"          -> st.asJson,
          "adlp_category"   -> adlp.asJson,
          "subtotal_ex_vat" -> sub.toString.asJson,
          "vat_total"       -> vat.toString.asJson,
          "total_inc_vat"   -> tot.toString.asJson,
          "lines" -> Json.fromValues(lines.map {
            case (lid, v, q, p, a, sched, lst) =>
              Json.obj(
                "id"                -> lid.toString.asJson,
                "variant"           -> v.toString.asJson,
                "qty"               -> q.asJson,
                "unit_price_ex_vat" -> p.toString.asJson,
                "adlp_category"     -> a.asJson,
                "is_scheduled"      -> sched.asJson,
                "status"            -> lst.asJson,
                "tranches" -> Json.fromValues(
                  byLine.getOrElse(lid, Nil).sortBy(_._2).map {
                    case (_, seq, tq, ts) =>
                      Json.obj("seq" -> seq.asJson, "qty" -> tq.asJson, "status" -> ts.asJson)
                  }
                )
              )
          })
        )
    }

  // Order worklist (doc 06): the historical order register, newest first, with the buying account name and a line
  // count. Optional market/status filters and a free-text match over order number or customer.
  def list(market: Option[UUID], status: Option[String], q: Option[String], limit: Int): ConnectionIO[List[Json]] =
    (fr"""SELECT o.id, o.order_no, o.status, o.type, o.adlp_category, o.txn_currency,
                 o.subtotal_ex_vat, o.total_inc_vat, o.order_date, p.display_name,
                 (SELECT count(*) FROM order_line ol WHERE ol.order_id = o.id)
          FROM "order" o JOIN party p ON p.id = o.sold_to_party_id""" ++
      Fragments.whereAndOpt(
        market.map(mk => fr"o.market_id = $mk"),
        status.map(s => fr"o.status = $s"),
        q.map(term => fr"(o.order_no ILIKE ${"%" + term + "%"} OR p.display_name ILIKE ${"%" + term + "%"})")
      ) ++ fr"ORDER BY o.order_date DESC LIMIT $limit")
      .query[(UUID, String, String, String, String, String, BigDecimal, BigDecimal, Instant, String, Int)]
      .to[List]
      .map(_.map {
        case (id, no, st, tpe, adlp, ccy, sub, total, date, customer, lines) =>
          Json.obj(
            "id"              -> id.toString.asJson,
            "order_no"        -> no.asJson,
            "status"          -> st.asJson,
            "type"            -> tpe.asJson,
            "adlp_category"   -> adlp.asJson,
            "currency"        -> ccy.asJson,
            "subtotal_ex_vat" -> sub.toString.asJson,
            "total_inc_vat"   -> total.toString.asJson,
            "order_date"      -> date.toString.asJson,
            "customer"        -> customer.asJson,
            "lines"           -> lines.asJson
          )
      })
}
