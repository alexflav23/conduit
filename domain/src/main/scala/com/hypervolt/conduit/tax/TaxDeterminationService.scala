package com.hypervolt.conduit.tax

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import doobie._
import doobie.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID

// The determination entry point (doc 16 §4.1): resolve the provider from `tax_routing`, run it, persist the
// immutable reproducible tax_quote (superseding the prior for this order/context), advance US/CA nexus, and emit
// `tax.quoted`. One Postgres transaction; the provider abstraction means the caller never knows rate-table vs vendor.
// An external provider that is routed-to but not registered FAILS CLOSED at a binding context (422) — never books
// zero/guessed tax — and only falls back to a rate-table estimate for a non-binding preview (doc 16 §4.4-fallback).
final class TaxDeterminationService[F[_]: Async](xa: Transactor[F], providers: Map[String, TaxProvider]) {

  private val bindingContexts = Set("order_placed", "invoice", "intercompany_import")

  def determine(req: TaxQuoteRequest): F[Either[String, TaxQuoteResponse]] = {
    val facts = TaxClassifier.classify(req)
    TaxQuoteRepo
      .provider(req.shipTo.jurisdiction, facts.taxType, req.asOf)
      .flatMap(name =>
        providers.get(name) match {
          case Some(p) => runAndPersist(req, facts, p, name).map(_.asRight[String])
          case None if !bindingContexts.contains(req.context) =>
            runAndPersist(req, facts, providers("rate_table"), "rate_table").map(_.asRight[String])
          case None =>
            s"tax_determination_unavailable: provider '$name' not registered"
              .asLeft[TaxQuoteResponse]
              .pure[ConnectionIO]
        }
      )
      .transact(xa)
  }

  private def runAndPersist(
      req: TaxQuoteRequest,
      facts: SupplyFacts,
      provider: TaxProvider,
      providerName: String
  ): ConnectionIO[TaxQuoteResponse] =
    provider.quote(req).flatMap { resp =>
      val quoteId = UUID.randomUUID()
      TaxQuoteRepo.insertQuote(quoteId, req, resp, providerName) *>
        TaxQuoteRepo.insertLines(quoteId, resp) *>
        TaxQuoteRepo.supersedePrior(req, quoteId) *>
        updateNexus(req, resp, facts) *>
        OutboxRepo.append(quotedEvent(quoteId, req, resp)).as(resp)
    }

  // US/CA economic-nexus rolling totals + threshold alerts (doc 16 §4.5). Only advances a configured profile;
  // crossing emits tax.nexus.threshold_crossed (a register-then-collect action), 80% emits …_approaching.
  private def updateNexus(req: TaxQuoteRequest, resp: TaxQuoteResponse, facts: SupplyFacts): ConnectionIO[Unit] =
    (req.shipTo.region, facts.taxType) match {
      case (Some(region), tt) if (req.shipTo.jurisdiction == "US" || req.shipTo.jurisdiction == "CA") && tt != "VAT" =>
        TaxQuoteRepo.nexus(req.entityId, req.shipTo.jurisdiction, region).flatMap {
          case None => ().pure[ConnectionIO]
          case Some(n) =>
            val sales       = n.salesToDate + resp.taxableTotal
            val txns        = n.txnCountToDate + 1
            val byAmt       = n.thresholdAmount.filter(_ > 0).map(t => sales / t)
            val byTxn       = n.thresholdTxnCount.filter(_ > 0).map(t => BigDecimal(txns) / t)
            val pct         = (byAmt.toList ++ byTxn.toList).maxOption.getOrElse(BigDecimal(0))
            val crossed     = pct >= 1 && n.status != "crossed" && n.status != "registered"
            val approaching = pct >= BigDecimal("0.8") && n.status == "monitoring"
            val status      = if (crossed) "crossed" else if (approaching) "approaching" else n.status
            TaxQuoteRepo.advanceNexus(n.id, resp.taxableTotal, status, crossed) *>
              (if (crossed) OutboxRepo.append(nexusEvent(req, region, sales, txns, "threshold_crossed")).void
               else if (approaching)
                 OutboxRepo.append(nexusEvent(req, region, sales, txns, "threshold_approaching")).void
               else ().pure[ConnectionIO])
        }
      case _ => ().pure[ConnectionIO]
    }

  private def quotedEvent(quoteId: UUID, req: TaxQuoteRequest, resp: TaxQuoteResponse): OutboxEvent = {
    val key = req.orderId.orElse(req.intercompanyLinkId).getOrElse(quoteId)
    OutboxEvent(
      UUID.randomUUID(),
      "tax.quoted",
      1,
      "tax",
      quoteId,
      key.toString,
      None,
      None,
      None,
      Json.obj(
        "tax_quote_id"         -> quoteId.toString.asJson,
        "context"              -> req.context.asJson,
        "order_id"             -> req.orderId.map(_.toString).asJson,
        "intercompany_link_id" -> req.intercompanyLinkId.map(_.toString).asJson,
        "entity_id"            -> req.entityId.toString.asJson,
        "supply_kind"          -> resp.supplyKind.asJson,
        "provider"             -> resp.provider.asJson,
        "reverse_charge"       -> resp.reverseCharge.asJson,
        "currency"             -> resp.currency.asJson,
        "total_tax"            -> resp.taxTotal.asJson,
        "rounding_policy"      -> resp.roundingPolicy.asJson,
        "rates_asof"           -> resp.ratesAsof.toString.asJson
      ),
      Instant.now(),
      "service:tax"
    )
  }

  private def nexusEvent(
      req: TaxQuoteRequest,
      region: String,
      sales: BigDecimal,
      txns: Int,
      kind: String
  ): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(),
      s"tax.nexus.$kind",
      1,
      "tax",
      req.entityId,
      req.entityId.toString,
      None,
      None,
      None,
      Json.obj(
        "entity_id"         -> req.entityId.toString.asJson,
        "jurisdiction"      -> req.shipTo.jurisdiction.asJson,
        "region"            -> region.asJson,
        "sales_to_date"     -> sales.asJson,
        "txn_count_to_date" -> txns.asJson,
        "status"            -> (if (kind == "threshold_crossed") "crossed" else "approaching").asJson
      ),
      Instant.now(),
      "service:tax"
    )
}
