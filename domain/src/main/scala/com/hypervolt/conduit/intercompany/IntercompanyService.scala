package com.hypervolt.conduit.intercompany

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.LedgerFlags
import com.hypervolt.conduit.ledger.LedgerTransfer
import com.hypervolt.conduit.ledger.LedgerTransferCode
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.money.RoundingPolicy
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

final case class MovementResult(
    linkId: UUID,
    status: String,
    transferPriceTotal: BigDecimal,
    tpCurrency: String,
    landedTotal: BigDecimal,
    marginTotal: BigDecimal,
    fxRate: BigDecimal,
    fxBasis: Option[String],
    isCrossBorder: Boolean,
    importTaxStatus: String,
    sellTbTransferId: BigInt,
    buyTbTransferId: BigInt,
    fxBridgeTbTransferId: Option[BigInt]
)

private final case class LineCalc(lot: LotRow, qty: Int, tpUnit: BigDecimal, lineLanded: BigDecimal, lineTp: BigDecimal)
private final case class Prepared(accounts: List[LedgerAccount], transfers: List[LedgerTransfer])

// The intercompany movement (doc 13 §3): one hop, a set of specific lots → a sell leg + buy leg linked by
// intercompany_link, two (or three, cross-currency) LINKED TigerBeetle transfers, a reproducible tp_document per
// lot, and a buy-side import-tax call when cross-border. One Postgres transaction + one outbox row; the ledger
// legs are deterministic from the movement event_id so redelivery is a no-op.
final class IntercompanyService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F], tax: TaxEngine) {

  private val rounding = RoundingPolicy.HalfUp

  private def minor(amount: BigDecimal, ccy: Currency): BigInt =
    (amount.setScale(ccy.minorUnits, RoundingMode.HALF_UP) * BigDecimal(10).pow(ccy.minorUnits)).toBigInt

  def move(
      fromEntityId: UUID,
      toEntityId: UUID,
      variant: UUID,
      lotIds: List[UUID],
      asOf: LocalDate,
      actor: UUID
  ): F[Either[String, MovementResult]] =
    build(fromEntityId, toEntityId, variant, lotIds, asOf, actor).transact(xa).flatMap {
      case Left(e) => e.asLeft[MovementResult].pure[F]
      case Right((prep, res)) =>
        ledger.createAccounts(prep.accounts) *>
          ledger.postTransfers(prep.transfers) *>
          res.asRight[String].pure[F]
    }

  // Everything (validation, pricing, FX, proxies, inserts, outbox) in ONE transaction; the TB legs are posted
  // after commit but their ids are already recorded on the link (deterministic), so a re-post is a no-op.
  private def build(
      from: UUID,
      to: UUID,
      variant: UUID,
      lotIds: List[UUID],
      asOf: LocalDate,
      actor: UUID
  ): ConnectionIO[Either[String, (Prepared, MovementResult)]] =
    (
      IcRepo.entityNode(from),
      IcRepo.entityNode(to),
      IcRepo.allEntities,
      IcRepo.activePolicy(from, to, variant, asOf)
    ).tupled
      .flatMap {
        case (None, _, _, _) => leftC(s"unknown from-entity $from")
        case (_, None, _, _) => leftC(s"unknown to-entity $to")
        case (_, _, _, None) => leftC(s"no active transfer-price policy for $from→$to / $variant")
        case (Some(fe), Some(te), all, Some(pol)) =>
          Topology.procurementChain(to, all) match {
            case Left(e) => leftC(e)
            case Right(chain) =>
              val hopSeq      = chain.hops.find(h => h.from.id == from && h.to.id == to).map(_.hopSeq)
              val crossBorder = fe.jurisdiction != te.jurisdiction
              priceLots(pol, variant, lotIds).flatMap {
                case Left(e)      => leftC(e)
                case Right(lines) => assemble(fe, te, variant, pol, lines, hopSeq, crossBorder, asOf, actor)
              }
          }
      }

  private def priceLots(
      pol: PolicyRow,
      variant: UUID,
      lotIds: List[UUID]
  ): ConnectionIO[Either[String, List[LineCalc]]] =
    (lotIds.traverse(IcRepo.lot), IcRepo.resaleAnchor(variant)).mapN { (lots, anchor) =>
      for {
        m   <- TransferPricing.Method.fromCode(pol.method).toRight(s"unknown tp method ${pol.method}")
        lvs <- lots.zip(lotIds).traverse { case (opt, id) => opt.toRight(s"lot $id not found") }
        _ <-
          lvs
            .find(_.productVariantId != variant)
            .fold(().asRight[String])(l => s"lot ${l.id} is not variant $variant".asLeft)
        out <- lvs.traverse { lot =>
          val ccy = Currency.fromCode(lot.currency).getOrElse(Currency.fromCode("GBP").get)
          TransferPricing
            .unitPrice(
              TransferPricing.Policy(m, pol.markupPct, pol.resaleMarginPct, pol.fixedPrice),
              lot.landedUnitCost,
              anchor,
              ccy,
              rounding
            )
            .map(tp => LineCalc(lot, lot.qty, tp, lot.landedUnitCost * lot.qty, tp * lot.qty))
        }
      } yield out
    }

  private def assemble(
      fe: EntityNode,
      te: EntityNode,
      variant: UUID,
      pol: PolicyRow,
      lines: List[LineCalc],
      hopSeq: Option[Int],
      crossBorder: Boolean,
      asOf: LocalDate,
      actor: UUID
  ): ConnectionIO[Either[String, (Prepared, MovementResult)]] = {
    val sellCcy   = Currency.fromCode(fe.functionalCurrency).getOrElse(Currency.fromCode("GBP").get)
    val buyCcy    = Currency.fromCode(te.functionalCurrency).getOrElse(Currency.fromCode("GBP").get)
    val landedTot = lines.map(_.lineLanded).sum
    val tpSell    = lines.map(_.lineTp).sum
    val marginTot = tpSell - landedTot
    val totalQty  = lines.map(_.qty).sum
    val periodKey = f"${asOf.getYear}%04d-${asOf.getMonthValue}%02d"
    val linkId    = UUID.randomUUID()
    val eventId   = UUID.randomUUID()

    val lockCheck = (IcRepo.periodLocked(fe.id, periodKey), IcRepo.periodLocked(te.id, periodKey)).mapN(_ || _)
    val fxResolve =
      if (sellCcy.code == buyCcy.code)
        (BigDecimal(1), Option.empty[String], Option.empty[(UUID, BigDecimal)]).pure[ConnectionIO]
      else
        IcRepo.activeHedge(sellCcy.code, buyCcy.code, te.id, asOf, tpSell).flatMap {
          case Some(h) => (h.contractedRate, Option("hedged"), Option((h.id, tpSell))).pure[ConnectionIO]
          case None =>
            IcRepo.spotRate(sellCcy.code, buyCcy.code, asOf).map {
              case Some(r) => (r, Option("spot"), Option.empty[(UUID, BigDecimal)])
              case None    => (BigDecimal(1), Option("spot"), Option.empty[(UUID, BigDecimal)])
            }
        }

    (lockCheck, fxResolve).tupled.flatMap {
      case (true, _) => leftC(s"period $periodKey is locked")
      case (false, (fxRate, fxBasis, hedgeDraw)) =>
        val tpBuy = (tpSell * fxRate)
        // ----- ledger legs (minor units), deterministic transfer ids from eventId -----
        val icFromTo   = TbIds.accountId(s"IC:${fe.id}:${te.id}")
        val icToFrom   = TbIds.accountId(s"IC:${te.id}:${fe.id}")
        val invFrom    = TbIds.accountId(s"INV:${fe.id}")
        val invTo      = TbIds.accountId(s"INV:${te.id}")
        val marginA    = TbIds.accountId(s"IC_MARGIN:${fe.id}")
        val fxSell     = TbIds.accountId(s"FX_CLEARING:${sellCcy.code}")
        val fxBuy      = TbIds.accountId(s"FX_CLEARING:${buyCcy.code}")
        val sellLedger = Ledgers.forCurrency(sellCcy)
        val buyLedger  = Ledgers.forCurrency(buyCcy)

        val landedM = minor(landedTot, sellCcy)
        val marginM = minor(marginTot, sellCcy)
        val tpSellM = minor(tpSell, sellCcy)
        val tpBuyM  = minor(tpBuy, buyCcy)

        def tf(leg: Int, dr: BigInt, cr: BigInt, amt: BigInt, led: Int) =
          LedgerTransfer(TbIds.transferId(eventId, leg), dr, cr, amt, led, LedgerTransferCode.Intercompany)

        val sameCcy = sellCcy.code == buyCcy.code
        val rawLegs =
          if (sameCcy)
            List(
              tf(0, icFromTo, invFrom, landedM, sellLedger),
              tf(1, icFromTo, marginA, marginM, sellLedger),
              tf(2, invTo, icToFrom, tpSellM, buyLedger)
            )
          else
            List(
              tf(0, icFromTo, invFrom, landedM, sellLedger),
              tf(1, icFromTo, marginA, marginM, sellLedger),
              tf(2, fxSell, icFromTo, tpSellM, sellLedger),
              tf(3, invTo, fxBuy, tpBuyM, buyLedger)
            )
        val nonZero = rawLegs.filter(_.amount > 0)
        // TB linked chain: every leg but the LAST carries the Linked flag (commit all-or-none).
        val legs = nonZero.zipWithIndex.map {
          case (t, i) => if (i < nonZero.size - 1) t.copy(flags = LedgerFlags.Linked) else t
        }

        val accounts = {
          val base = List(
            LedgerAccount(icFromTo, sellLedger, LedgerAccountCode.Intercompany),
            LedgerAccount(invFrom, sellLedger, LedgerAccountCode.Inv),
            LedgerAccount(marginA, sellLedger, LedgerAccountCode.IcMargin),
            LedgerAccount(icToFrom, buyLedger, LedgerAccountCode.Intercompany),
            LedgerAccount(invTo, buyLedger, LedgerAccountCode.Inv)
          )
          if (sameCcy) base
          else
            base ++ List(
              LedgerAccount(fxSell, sellLedger, LedgerAccountCode.FxClearing),
              LedgerAccount(fxBuy, buyLedger, LedgerAccountCode.FxClearing)
            )
        }

        val sellTbId   = TbIds.transferId(eventId, 0)
        val buyTbId    = TbIds.transferId(eventId, if (sameCcy) 2 else 3)
        val fxBridgeId = if (sameCcy) None else Some(TbIds.transferId(eventId, 2))

        // ----- persistence: proxies, sell/buy legs, stock transfer, link, tp_documents, import-tax, outbox -----
        for {
          sellParty <- IcRepo.proxyParty(te)
          buySupp   <- IcRepo.proxySupplier(fe)
          fromLoc   <- IcRepo.proxyLocation(fe)
          toLoc     <- IcRepo.proxyLocation(te)
          sellOrder <- insertSellOrder(linkId, fe.id, sellParty, sellCcy.code, tpSell, actor)
          buyPo     <- insertBuyPo(linkId, te.id, buySupp, buyCcy.code, tpBuy)
          stx       <- insertStockTransfer(fromLoc, toLoc, te.id, variant, totalQty)
          importTax <-
            if (crossBorder) tax.quote(buildTaxContext(linkId, fe, te, variant, lines, sellCcy.code, asOf)).map(Some(_))
            else Option.empty[TaxQuoteResponse].pure[ConnectionIO]
          importStatus = if (crossBorder) "quoted" else "n/a"
          fxRateOpt    = if (sellCcy.code != buyCcy.code) Some(fxRate) else Option.empty[BigDecimal]
          _ <- insertLink(
            linkId,
            sellOrder,
            buyPo,
            fe.id,
            te.id,
            hopSeq,
            stx,
            tpSell,
            sellCcy.code,
            fxRateOpt,
            fxBasis,
            sellTbId,
            buyTbId,
            fxBridgeId.map(_.toString),
            importStatus,
            importTax.map(_.asJson),
            periodKey
          )
          _ <- lines.traverse_(l => insertTpDoc(linkId, fe.id, te.id, variant, l, pol, sellCcy.code))
          _ <- hedgeDraw.traverse_ { case (hid, amt) => IcRepo.drawHedge(hid, amt) *> outboxHedge(hid, amt) }
          _ <- OutboxRepo.append(
            movementEvent(
              eventId,
              linkId,
              fe,
              te,
              variant,
              lines,
              tpSell,
              sellCcy.code,
              fxRate,
              fxBasis,
              sellTbId,
              buyTbId,
              fxBridgeId,
              crossBorder,
              importTax,
              periodKey
            )
          )
        } yield Right(
          (
            Prepared(accounts, legs),
            MovementResult(
              linkId,
              "posted",
              tpSell,
              sellCcy.code,
              landedTot,
              marginTot,
              fxRate,
              fxBasis,
              crossBorder,
              importStatus,
              sellTbId,
              buyTbId,
              fxBridgeId
            )
          )
        )
    }
  }

  private def leftC(msg: String): ConnectionIO[Either[String, (Prepared, MovementResult)]] =
    Either.left[String, (Prepared, MovementResult)](msg).pure[ConnectionIO]

  // ----- inserts -----

  private def insertSellOrder(
      linkId: UUID,
      entity: UUID,
      soldTo: UUID,
      ccy: String,
      total: BigDecimal,
      actor: UUID
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency,
            subtotal_ex_vat, total_inc_vat, payment_method, created_by)
          VALUES (${"IC-S-" + linkId.toString.take(8)}, 'intercompany', $entity, $soldTo, $soldTo, 'placed', $ccy,
            $total, $total, 'intercompany', $actor) RETURNING id""".query[UUID].unique

  private def insertBuyPo(
      linkId: UUID,
      entity: UUID,
      supplier: UUID,
      ccy: String,
      total: BigDecimal
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO purchase_order (po_no, entity_id, supplier_id, type, status, txn_currency, total)
          VALUES (${"IC-P-" + linkId.toString.take(8)}, $entity, $supplier, 'intercompany', 'open', $ccy, $total)
          RETURNING id""".query[UUID].unique

  private def insertStockTransfer(
      fromLoc: UUID,
      toLoc: UUID,
      entity: UUID,
      variant: UUID,
      qty: Int
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO stock_transfer (from_location_id, to_location_id, entity_id, product_variant_id, qty, status)
          VALUES ($fromLoc, $toLoc, $entity, $variant, $qty, 'in_transit') RETURNING id""".query[UUID].unique

  private def insertLink(
      id: UUID,
      sellOrder: UUID,
      buyPo: UUID,
      from: UUID,
      to: UUID,
      hopSeq: Option[Int],
      stx: UUID,
      tpTotal: BigDecimal,
      tpCcy: String,
      fxRateOpt: Option[BigDecimal],
      fxBasis: Option[String],
      sellTb: BigInt,
      buyTb: BigInt,
      fxBridge: Option[String],
      importStatus: String,
      importTaxJson: Option[Json],
      periodKey: String
  ): ConnectionIO[Int] =
    sql"""INSERT INTO intercompany_link
            (id, sell_order_id, buy_po_id, status, from_entity_id, to_entity_id, hop_seq, stock_transfer_id,
             transfer_price_total, tp_currency, fx_rate, fx_basis, sell_tb_transfer_id, buy_tb_transfer_id,
             fx_bridge_tb_transfer_id, elimination_group_id, import_tax_status, import_tax, accounting_period_key)
          VALUES ($id, $sellOrder, $buyPo, 'posted', $from, $to, $hopSeq, $stx,
             $tpTotal, $tpCcy, $fxRateOpt, $fxBasis,
             ${sellTb.toString}::numeric, ${buyTb.toString}::numeric,
             ${fxBridge}::numeric, $id, $importStatus,
             $importTaxJson, $periodKey)""".update.run

  private def insertTpDoc(
      linkId: UUID,
      from: UUID,
      to: UUID,
      variant: UUID,
      l: LineCalc,
      pol: PolicyRow,
      tpCcy: String
  ): ConnectionIO[Int] = {
    val mm = pol.markupPct.orElse(pol.resaleMarginPct)
    val inputs = Json.obj(
      "method"               -> pol.method.asJson,
      "policy_version"       -> pol.version.asJson,
      "lot_landed_unit_cost" -> l.lot.landedUnitCost.asJson,
      "markup_or_margin_pct" -> mm.asJson,
      "fixed_price"          -> pol.fixedPrice.asJson,
      "qty"                  -> l.qty.asJson
    )
    sql"""INSERT INTO tp_document
            (intercompany_link_id, from_entity_id, to_entity_id, product_variant_id, lot_batch_id, policy_id,
             policy_version, method, documentation_method, lot_landed_unit_cost, markup_or_margin_pct, qty,
             transfer_unit_price, tp_currency, reproducible_inputs)
          VALUES ($linkId, $from, $to, $variant, ${l.lot.id}, ${pol.id}, ${pol.version}, ${pol.method},
             ${pol.documentationMethod}, ${l.lot.landedUnitCost}, $mm, ${l.qty}, ${l.tpUnit}, $tpCcy, $inputs)""".update.run
  }

  // ----- events -----

  private def movementEvent(
      eventId: UUID,
      linkId: UUID,
      fe: EntityNode,
      te: EntityNode,
      variant: UUID,
      lines: List[LineCalc],
      tpTotal: BigDecimal,
      tpCcy: String,
      fxRate: BigDecimal,
      fxBasis: Option[String],
      sellTb: BigInt,
      buyTb: BigInt,
      fxBridge: Option[BigInt],
      crossBorder: Boolean,
      importTax: Option[TaxQuoteResponse],
      periodKey: String
  ): OutboxEvent =
    OutboxEvent(
      eventId,
      "intercompany.movement.posted",
      1,
      "intercompany_link",
      linkId,
      linkId.toString,
      None,
      None,
      None,
      Json.obj(
        "intercompany_link_id" -> linkId.toString.asJson,
        "from_entity_id"       -> fe.id.toString.asJson,
        "to_entity_id"         -> te.id.toString.asJson,
        "from_currency"        -> fe.functionalCurrency.asJson,
        "to_currency"          -> te.functionalCurrency.asJson,
        "is_cross_border"      -> crossBorder.asJson,
        "lines" -> lines
          .map(l =>
            Json.obj(
              "product_variant_id"   -> variant.toString.asJson,
              "lot_batch_id"         -> l.lot.id.toString.asJson,
              "qty"                  -> l.qty.asJson,
              "transfer_unit_price"  -> l.tpUnit.asJson,
              "lot_landed_unit_cost" -> l.lot.landedUnitCost.asJson
            )
          )
          .asJson,
        "transfer_price_total"     -> tpTotal.asJson,
        "tp_currency"              -> tpCcy.asJson,
        "fx_rate"                  -> fxRate.asJson,
        "fx_basis"                 -> fxBasis.asJson,
        "sell_tb_transfer_id"      -> sellTb.toString.asJson,
        "buy_tb_transfer_id"       -> buyTb.toString.asJson,
        "fx_bridge_tb_transfer_id" -> fxBridge.map(_.toString).asJson,
        "elimination_group_id"     -> linkId.toString.asJson,
        "import_tax"               -> importTax.map(_.asJson).getOrElse(Json.obj("status" -> "n/a".asJson)),
        "accounting_period_key"    -> periodKey.asJson
      ),
      Instant.now()
    )

  private def outboxHedge(hedgeId: UUID, amount: BigDecimal): ConnectionIO[Int] =
    OutboxRepo.append(
      OutboxEvent(
        UUID.randomUUID(),
        "fx.hedge.updated",
        1,
        "fx_hedge",
        hedgeId,
        hedgeId.toString,
        None,
        None,
        None,
        Json.obj("fx_hedge_id" -> hedgeId.toString.asJson, "drawn" -> amount.asJson),
        Instant.now()
      )
    )

  private def buildTaxContext(
      linkId: UUID,
      fe: EntityNode,
      te: EntityNode,
      variant: UUID,
      lines: List[LineCalc],
      ccy: String,
      asOf: LocalDate
  ): TaxQuoteRequest =
    TaxQuoteRequest(
      context = "intercompany_import",
      shipFromJurisdiction = fe.jurisdiction,
      shipToJurisdiction = te.jurisdiction,
      shipToRegime = te.jurisdiction,
      lines = lines.map(l => TaxQuoteLine(variant, None, l.qty, l.lineTp, ccy)),
      movementRef = linkId,
      asOf = asOf
    )
}
