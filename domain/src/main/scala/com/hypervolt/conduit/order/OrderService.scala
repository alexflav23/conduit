package com.hypervolt.conduit.order

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.pricing.PriceRuleRepo
import com.hypervolt.conduit.pricing.PricingService
import com.hypervolt.conduit.pricing.QuoteLine
import com.hypervolt.conduit.pricing.QuoteLineResult
import com.hypervolt.conduit.pricing.VariantRepo
import doobie.ConnectionIO
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

private final case class LinePricing(variantId: UUID, line: PlaceLineInput, priced: QuoteLineResult)

// Order capture (doc 04 §Orders/§ADLP/§Credit). An exception line holds the order `pending_ceo`
// (no OrderPlaced fan-out) until CEO approval; a credit block rejects; tranches are independently
// fulfillable; pre-dispatch amendments re-price/re-ADLP and are audited.
final class OrderService[F[_]: Async](xa: Transactor[F]) {

  def place(in: PlaceOrderInput, asOf: Instant): F[Either[OrderError, PlacedOrder]] =
    priceLines(in.channelId, in.marketId, in.entityId, in.currency, in.lines, asOf).flatMap {
      case Left(err) => err.asLeft[PlacedOrder].pure[ConnectionIO]
      case Right(priced) =>
        val (subtotal, vat, total) = totals(priced)
        creditCheck(in.paymentMethod, in.billToPartyId, total).flatMap {
          case Some(err) => err.asLeft[PlacedOrder].pure[ConnectionIO]
          case None =>
            val hasException = priced.exists(_.priced.adlpCategory == "exception")
            val status       = if (hasException) "pending_ceo" else "placed"
            val adlp         = if (hasException) "exception" else "standard"
            insertGraph(in, priced, subtotal, vat, total, status, adlp, asOf).map(_.asRight[OrderError])
        }
    }.transact(xa)

  def amend(orderId: UUID, newLines: List[PlaceLineInput], reason: Option[String], actor: Option[UUID], asOf: Instant): F[Either[OrderError, PlacedOrder]] =
    OrderRepo.header(orderId).flatMap {
      case None => OrderError.NotFound(orderId.toString).asLeft[PlacedOrder].leftWiden[OrderError].pure[ConnectionIO]
      case Some(h) =>
        val amendable = (h.status == "placed" || h.status == "pending_ceo") &&
          h.dispatchedAt.isEmpty && h.amendCutoff.forall(c => asOf.isBefore(c))
        if (!amendable)
          OrderError.AmendRejected(s"order not amendable (status=${h.status})").asLeft[PlacedOrder].leftWiden[OrderError].pure[ConnectionIO]
        else
          priceLines(h.channelId, h.marketId, h.entityId, h.currency, newLines, asOf).flatMap {
            case Left(err) => err.asLeft[PlacedOrder].pure[ConnectionIO]
            case Right(priced) =>
              val (subtotal, vat, total) = totals(priced)
              val hasException = priced.exists(_.priced.adlpCategory == "exception")
              val status       = if (hasException) "pending_ceo" else "placed"
              val adlp         = if (hasException) "exception" else "standard"
              for {
                before <- OrderRepo.snapshotLines(orderId)
                _      <- OrderRepo.deleteLines(orderId)
                _      <- priced.traverse_(lp => insertLineWithTranches(orderId, lp, asOf))
                _      <- OrderRepo.updateTotalsAndStatus(orderId, status, adlp, subtotal, vat, total)
                after  <- OrderRepo.snapshotLines(orderId)
                _      <- OrderRepo.insertAmendment(orderId, actor, before, after, reason)
                _      <- OutboxRepo.append(amendedEvent(orderId, before, after, actor))
              } yield PlacedOrder(orderId, "", status, adlp, subtotal, vat, total).asRight[OrderError]
          }
    }.transact(xa)

  // ----- internals -----

  private def priceLines(
      channel: UUID,
      market: UUID,
      entity: Option[UUID],
      currency: String,
      lines: List[PlaceLineInput],
      asOf: Instant
  ): ConnectionIO[Either[OrderError, List[LinePricing]]] =
    lines.foldLeft((List.empty[LinePricing].asRight[OrderError]).pure[ConnectionIO]) { (accF, line) =>
      accF.flatMap {
        case Left(e) => e.asLeft[List[LinePricing]].pure[ConnectionIO]
        case Right(acc) =>
          VariantRepo.idBySku(line.sku).flatMap {
            case None => OrderError.UnknownSku(line.sku).asLeft[List[LinePricing]].leftWiden[OrderError].pure[ConnectionIO]
            case Some(vid) =>
              PriceRuleRepo.candidates(vid, channel, market, entity, currency, line.qty, asOf).map { candidates =>
                PricingService.resolve(candidates, channel, market, entity) match {
                  case None      => OrderError.NoPrice(line.sku).asLeft[List[LinePricing]]
                  case Some(res) => Right(acc :+ LinePricing(vid, line, PricingService.priceLine(res, QuoteLine(line.sku, line.qty, line.unitPriceExVat))))
                }
              }
          }
      }
    }

  private def totals(priced: List[LinePricing]): (BigDecimal, BigDecimal, BigDecimal) = {
    val subtotal = priced.map(p => (p.priced.unitPriceExVat * BigDecimal(p.priced.qty)).setScale(2, RoundingMode.HALF_UP)).foldLeft(BigDecimal(0))(_ + _)
    val vat      = priced.map(_.priced.vat).foldLeft(BigDecimal(0))(_ + _)
    (subtotal, vat, subtotal + vat)
  }

  private def creditCheck(paymentMethod: String, billTo: UUID, total: BigDecimal): ConnectionIO[Option[OrderError]] =
    if (paymentMethod == "stripe") Option.empty[OrderError].pure[ConnectionIO]
    else
      OrderRepo.creditProfile(billTo).flatMap {
        case None => Option[OrderError](OrderError.NotBillable("no credit profile and not a card payment")).pure[ConnectionIO]
        case Some(cp) =>
          OrderRepo.openOrdersTotal(billTo).map { open =>
            val exposure = open + total
            if (exposure > cp.creditLimit && cp.policy == "block") Some(OrderError.CreditBlocked(exposure - cp.creditLimit))
            else None
          }
      }

  private def insertLineWithTranches(orderId: UUID, lp: LinePricing, asOf: Instant): ConnectionIO[Unit] = {
    val scheduled = lp.line.schedule.nonEmpty
    OrderRepo.insertLine(orderId, lp.variantId, lp.priced, scheduled).flatMap { lineId =>
      val tranches =
        if (scheduled) lp.line.schedule
        else List(TrancheInput(1, lp.line.qty, asOf.atZone(ZoneOffset.UTC).toLocalDate))
      tranches.traverse_(t => OrderRepo.insertTranche(lineId, t.seq, t.qty, t.requestedDate)) *>
        (if (lp.priced.adlpCategory == "exception")
           OrderRepo.insertException(orderId, lineId, lp.priced.unitPriceExVat, lp.priced.appliedDiscountPct).void
         else ().pure[ConnectionIO])
    }
  }

  private def insertGraph(
      in: PlaceOrderInput,
      priced: List[LinePricing],
      subtotal: BigDecimal,
      vat: BigDecimal,
      total: BigDecimal,
      status: String,
      adlp: String,
      asOf: Instant
  ): ConnectionIO[PlacedOrder] =
    for {
      created <- OrderRepo.insertOrder(in, status, adlp, subtotal, vat, total, None)
      (orderId, orderNo) = created
      _ <- priced.traverse_(lp => insertLineWithTranches(orderId, lp, asOf))
      event = if (status == "pending_ceo") exceptionEvent(orderId, orderNo) else placedEvent(orderId, orderNo, in, priced, total)
      _ <- OutboxRepo.append(event)
      _ <- auditOrder(orderId, orderNo, status, in.createdBy)
    } yield PlacedOrder(orderId, orderNo, status, adlp, subtotal, vat, total)

  private def auditOrder(orderId: UUID, orderNo: String, status: String, actor: Option[UUID]): ConnectionIO[Int] =
    sql"""INSERT INTO audit_log (entity_type, entity_id, action, after, actor_user_id)
          VALUES ('order', $orderId, 'create', ${Json.obj("order_no" -> orderNo.asJson, "status" -> status.asJson)}, $actor)""".update.run

  private def lineJson(priced: List[LinePricing]): Json =
    Json.fromValues(priced.map(lp =>
      Json.obj(
        "variant"            -> lp.variantId.toString.asJson,
        "qty"                -> lp.priced.qty.asJson,
        "unit_price_ex_vat"  -> lp.priced.unitPriceExVat.toString.asJson,
        "adlp_category"      -> lp.priced.adlpCategory.asJson,
        "tranches"           -> Json.fromValues((if (lp.line.schedule.nonEmpty) lp.line.schedule else Nil).map(t => Json.obj("seq" -> t.seq.asJson, "qty" -> t.qty.asJson)))
      )
    ))

  private def placedEvent(orderId: UUID, orderNo: String, in: PlaceOrderInput, priced: List[LinePricing], total: BigDecimal): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(), "order.placed", 1, "order", orderId, orderId.toString,
      Some(Json.obj("entity_id" -> in.entityId.map(_.toString).asJson, "market_id" -> in.marketId.toString.asJson, "channel_id" -> in.channelId.toString.asJson)),
      None, None,
      Json.obj("order_no" -> orderNo.asJson, "sold_to" -> in.soldToPartyId.toString.asJson, "bill_to" -> in.billToPartyId.toString.asJson, "total_inc_vat" -> total.toString.asJson, "lines" -> lineJson(priced)),
      Instant.now()
    )

  private def exceptionEvent(orderId: UUID, orderNo: String): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(), "adlp.exception.requested", 1, "order", orderId, orderId.toString,
      None, None, None, Json.obj("order_no" -> orderNo.asJson, "status" -> "pending_ceo".asJson), Instant.now()
    )

  private def amendedEvent(orderId: UUID, before: Json, after: Json, actor: Option[UUID]): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(), "order.amended", 1, "order", orderId, orderId.toString,
      None, None, None, Json.obj("before" -> before, "after" -> after, "actor" -> actor.map(_.toString).asJson), Instant.now()
    )
}
