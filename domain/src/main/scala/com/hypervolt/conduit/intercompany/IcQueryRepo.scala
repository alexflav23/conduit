package com.hypervolt.conduit.intercompany

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.util.UUID

// Read-side for the intercompany surface (doc 13 §8): policies, movements, TP documents, topology, and the
// consolidation/elimination read-model. Field names match field_layer_map so the route projection strips
// inter_entity / treasury columns for principals lacking the layer.
object IcQueryRepo {

  def policies(from: Option[UUID], to: Option[UUID], status: Option[String]): ConnectionIO[List[Json]] = {
    val base = fr"""SELECT id, from_entity_id, to_entity_id, method, markup_pct, resale_margin_pct, fixed_price,
                           tp_currency, status, version, documentation_method
                    FROM transfer_price_policy"""
    val conds = List(
      from.map(f => fr"from_entity_id = $f"),
      to.map(t => fr"to_entity_id = $t"),
      status.map(s => fr"status = $s")
    ).flatten
    val where = if (conds.isEmpty) Fragment.empty else fr"WHERE" ++ conds.reduce((a, b) => a ++ fr"AND" ++ b)
    (base ++ where ++ fr"ORDER BY from_entity_id, to_entity_id, version DESC LIMIT 200")
      .query[
        (
            UUID,
            UUID,
            UUID,
            String,
            Option[BigDecimal],
            Option[BigDecimal],
            Option[BigDecimal],
            Option[String],
            String,
            Int,
            Option[String]
        )
      ]
      .to[List]
      .map(_.map {
        case (id, f, t, m, mk, rm, fp, tc, st, v, dm) =>
          Json.obj(
            "id"                   -> id.toString.asJson,
            "from_entity_id"       -> f.toString.asJson,
            "to_entity_id"         -> t.toString.asJson,
            "method"               -> m.asJson,
            "markup_pct"           -> mk.asJson,
            "resale_margin_pct"    -> rm.asJson,
            "fixed_price"          -> fp.asJson,
            "tp_currency"          -> tc.asJson,
            "status"               -> st.asJson,
            "version"              -> v.asJson,
            "documentation_method" -> dm.asJson
          )
      })
  }

  def movements(from: Option[UUID], to: Option[UUID], status: Option[String]): ConnectionIO[List[Json]] = {
    val base = fr"""SELECT id, from_entity_id, to_entity_id, status, hop_seq, transfer_price_total, tp_currency,
                           fx_rate, fx_basis, import_tax_status, accounting_period_key, stock_transfer_id
                    FROM intercompany_link"""
    val conds = List(
      from.map(f => fr"from_entity_id = $f"),
      to.map(t => fr"to_entity_id = $t"),
      status.map(s => fr"status = $s")
    ).flatten
    val where = if (conds.isEmpty) Fragment.empty else fr"WHERE" ++ conds.reduce((a, b) => a ++ fr"AND" ++ b)
    (base ++ where ++ fr"ORDER BY created_at DESC LIMIT 200")
      .query[
        (
            UUID,
            UUID,
            UUID,
            String,
            Option[Int],
            BigDecimal,
            Option[String],
            Option[BigDecimal],
            Option[String],
            Option[String],
            Option[String],
            Option[UUID]
        )
      ]
      .to[List]
      .map(_.map {
        case (id, f, t, st, hop, tpt, tc, fx, fb, its, pk, stx) =>
          Json.obj(
            "id"                    -> id.toString.asJson,
            "from_entity_id"        -> f.toString.asJson,
            "to_entity_id"          -> t.toString.asJson,
            "status"                -> st.asJson,
            "hop_seq"               -> hop.asJson,
            "transfer_price_total"  -> tpt.asJson,
            "tp_currency"           -> tc.asJson,
            "fx_rate"               -> fx.asJson,
            "fx_basis"              -> fb.asJson,
            "import_tax_status"     -> its.asJson,
            "accounting_period_key" -> pk.asJson,
            "stock_transfer_id"     -> stx.map(_.toString).asJson
          )
      })
  }

  def tpDocuments(linkId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT d.id, v.sku, d.lot_batch_id, d.method, d.policy_version, d.lot_landed_unit_cost,
                 d.markup_or_margin_pct, d.qty, d.transfer_unit_price, d.tp_currency
          FROM tp_document d JOIN product_variant v ON v.id = d.product_variant_id
          WHERE d.intercompany_link_id = $linkId ORDER BY d.lot_landed_unit_cost"""
      .query[(UUID, String, UUID, String, Int, BigDecimal, Option[BigDecimal], Int, BigDecimal, String)]
      .to[List]
      .map(_.map {
        case (id, sku, lot, m, pv, landed, mm, qty, tp, tc) =>
          Json.obj(
            "id"                   -> id.toString.asJson,
            "sku"                  -> sku.asJson,
            "lot_batch_id"         -> lot.toString.asJson,
            "method"               -> m.asJson,
            "policy_version"       -> pv.asJson,
            "lot_landed_unit_cost" -> landed.asJson,
            "markup_or_margin_pct" -> mm.asJson,
            "qty"                  -> qty.asJson,
            "transfer_unit_price"  -> tp.asJson,
            "tp_currency"          -> tc.asJson
          )
      })

  // Topology for an operating entity (doc 13 §1.2): the chain of hops up to the external root.
  def topology(operating: UUID): ConnectionIO[Json] =
    IcRepo.allEntities.map { all =>
      Topology.procurementChain(operating, all) match {
        case Left(e) => Json.obj("error" -> e.asJson)
        case Right(chain) =>
          Json.obj(
            "external_root" -> chain.externalRoot.id.toString.asJson,
            "chain" -> chain.hops
              .map(h =>
                Json.obj(
                  "from"            -> h.from.id.toString.asJson,
                  "to"              -> h.to.id.toString.asJson,
                  "hop_seq"         -> h.hopSeq.asJson,
                  "from_currency"   -> h.fromCurrency.asJson,
                  "to_currency"     -> h.toCurrency.asJson,
                  "is_cross_border" -> h.isCrossBorder.asJson
                )
              )
              .asJson
          )
      }
    }

  // Intercompany clearing balances per ordered pair for a period (doc 13 §7 — must reconcile across mirrors).
  def intercompanyBalances(period: String): ConnectionIO[List[Json]] =
    sql"""SELECT from_entity_id, to_entity_id, tp_currency, COALESCE(SUM(transfer_price_total),0)
          FROM intercompany_link WHERE accounting_period_key = $period AND status IN ('posted','completed')
          GROUP BY from_entity_id, to_entity_id, tp_currency ORDER BY from_entity_id, to_entity_id"""
      .query[(UUID, UUID, Option[String], BigDecimal)]
      .to[List]
      .map(_.map {
        case (f, t, c, bal) =>
          Json.obj(
            "from_entity"         -> f.toString.asJson,
            "to_entity"           -> t.toString.asJson,
            "currency"            -> c.asJson,
            "ic_clearing_balance" -> bal.asJson
          )
      })

  // Unrealised intragroup margin per elimination group (doc 13 §7.1). Year-1: all imported inventory is still
  // held by the group (no per-lot external-sale tracking yet), so the full markup is unrealised.
  def eliminations(period: String): ConnectionIO[List[Json]] =
    sql"""SELECT l.elimination_group_id, l.from_entity_id, l.to_entity_id,
                 COALESCE(SUM(d.transfer_unit_price * d.qty - d.lot_landed_unit_cost * d.qty),0)
          FROM intercompany_link l JOIN tp_document d ON d.intercompany_link_id = l.id
          WHERE l.accounting_period_key = $period AND l.status IN ('posted','completed')
          GROUP BY l.elimination_group_id, l.from_entity_id, l.to_entity_id"""
      .query[(Option[UUID], UUID, UUID, BigDecimal)]
      .to[List]
      .map(_.map {
        case (g, f, t, m) =>
          Json.obj(
            "elimination_group_id" -> g.map(_.toString).asJson,
            "ic_pair"              -> s"$f→$t".asJson,
            "unrealised_margin"    -> m.asJson
          )
      })

  // Consolidated USD translation (doc 13 §7.2, ASC 830): each entity's intercompany flow translated to USD at a
  // provenanced rate (hedged where designated on the link, else the period closing rate).
  def translate(period: String): ConnectionIO[Json] =
    sql"""SELECT l.from_entity_id, e.functional_currency, COALESCE(SUM(l.transfer_price_total),0),
                 COALESCE(MAX(r.rate), 1.0), COALESCE(MAX(r.source), 'identity')
          FROM intercompany_link l JOIN entity e ON e.id = l.from_entity_id
          LEFT JOIN exchange_rate r ON r.base = e.functional_currency AND r.quote = 'USD' AND r.rate_type IN ('closing','spot')
          WHERE l.accounting_period_key = $period AND l.status IN ('posted','completed')
          GROUP BY l.from_entity_id, e.functional_currency"""
      .query[(UUID, String, BigDecimal, BigDecimal, String)]
      .to[List]
      .map { rows =>
        val perEntity = rows.map {
          case (e, ccy, total, rate, src) =>
            Json.obj(
              "entity_id"       -> e.toString.asJson,
              "functional_ccy"  -> ccy.asJson,
              "flow_functional" -> total.asJson,
              "rate"            -> rate.asJson,
              "rate_source"     -> src.asJson,
              "flow_usd"        -> (total * rate).setScale(2).asJson
            )
        }
        Json.obj(
          "period_key"            -> period.asJson,
          "presentation_currency" -> "USD".asJson,
          "per_entity"            -> perEntity.asJson,
          "total_usd"             -> rows.map { case (_, _, total, rate, _) => (total * rate) }.sum.setScale(2).asJson
        )
      }
}
