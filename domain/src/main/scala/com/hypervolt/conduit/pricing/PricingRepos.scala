package com.hypervolt.conduit.pricing

import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID

object VariantRepo {
  def idBySku(sku: String): ConnectionIO[Option[UUID]] =
    sql"SELECT id FROM product_variant WHERE sku = $sku".query[UUID].option

  // (id, product_class) — the class is the dimension cumulative tiers/rebates count over (doc 24 §4.5).
  def lookupBySku(sku: String): ConnectionIO[Option[(UUID, String)]] =
    sql"SELECT id, product_class FROM product_variant WHERE sku = $sku".query[(UUID, String)].option
}

object PriceRuleRepo {

  private type CandidateRow =
    (
        UUID,
        Option[UUID],
        String,
        Option[UUID],
        Option[UUID],
        Option[UUID],
        BigDecimal,
        BigDecimal,
        Int,
        Option[Int],
        Int,
        String,
        BigDecimal
    )

  // Agreement-aware tier candidates (doc 24 §2). LEFT JOIN price_agreement so a legacy rule with no agreement still
  // behaves as the open_list (today's standard list). For a real agreement we enforce its active window and its
  // customer scope: open_list applies to everyone; customer_set applies only when the buyer is in the set
  // (segment/sector are wired in slice 4). The band ceiling (up_to_qty) bounds the qty-eligible bands.
  def candidates(
      variantId: UUID,
      channel: UUID,
      market: UUID,
      entity: Option[UUID],
      currency: String,
      qty: Int,
      customer: Option[UUID],
      asOf: Instant
  ): ConnectionIO[List[PriceRuleCandidate]] =
    sql"""SELECT pr.id, pa.id, COALESCE(pa.applies_to, 'open_list'),
                 pr.channel_id, pr.market_id, pr.entity_id, pr.authorised_price, pr.max_discount_pct,
                 pr.min_qty, pr.up_to_qty, pr.version, pr.tax_regime, tr.rate_percent
          FROM price_rule pr
          JOIN tax_regime tr ON tr.code = pr.tax_regime
          LEFT JOIN price_agreement pa ON pa.id = pr.price_agreement_id
          WHERE pr.surface = 'customer' AND pr.product_variant_id = $variantId AND pr.currency = $currency
            AND pr.status = 'active'
            AND pr.effective_from <= $asOf AND (pr.effective_to IS NULL OR pr.effective_to > $asOf)
            AND (pr.channel_id = $channel OR pr.channel_id IS NULL)
            AND (pr.market_id = $market OR pr.market_id IS NULL)
            AND (pr.entity_id = $entity OR pr.entity_id IS NULL)
            AND pr.min_qty <= $qty
            AND (pr.up_to_qty IS NULL OR pr.up_to_qty >= $qty)
            AND (pa.id IS NULL OR pa.base_volume_basis = 'per_order')
            AND (pa.id IS NULL
                 OR (pa.status = 'active' AND pa.valid_from <= $asOf AND (pa.valid_to IS NULL OR pa.valid_to > $asOf)))
            AND (pa.id IS NULL
                 OR pa.applies_to = 'open_list'
                 OR (pa.applies_to = 'customer_set' AND $customer IS NOT NULL
                     AND EXISTS (SELECT 1 FROM price_agreement_customer pac
                                 WHERE pac.agreement_id = pa.id AND pac.party_id = $customer))
                 OR (pa.applies_to = 'segment' AND $customer IS NOT NULL
                     AND EXISTS (SELECT 1 FROM party p WHERE p.id = $customer AND p.segment = pa.scope_value))
                 OR (pa.applies_to = 'sector' AND $customer IS NOT NULL
                     AND EXISTS (SELECT 1 FROM party p WHERE p.id = $customer AND p.sector = pa.scope_value)))"""
      .query[CandidateRow]
      .to[List]
      .map(_.map {
        case (id, agr, applies, ch, mk, en, price, disc, minQ, upTo, ver, tr, rate) =>
          PriceRuleCandidate(id, agr, applies, ch, mk, en, price, disc, minQ, upTo, ver, tr, rate)
      })

  // Cumulative (base_volume_basis <> 'per_order') tier bands applicable to the customer — NOT qty-filtered (the band
  // is selected by the running cumulative position, not the line qty, doc 24 §4(b)). Carries the agreement's
  // valid_from so the resolver can derive the contract-year window. open_list cumulative is possible but unusual.
  private type CumBandRow =
    (
        UUID,
        UUID,
        String,
        String,
        Instant,
        Option[UUID],
        Option[UUID],
        Option[UUID],
        BigDecimal,
        BigDecimal,
        Int,
        Option[Int],
        Int,
        String,
        BigDecimal
    )

  def cumulativeBands(
      variantId: UUID,
      currency: String,
      customer: Option[UUID],
      asOf: Instant
  ): ConnectionIO[List[CumulativeBand]] =
    sql"""SELECT pr.id, pa.id, pa.applies_to, pa.base_volume_basis, pa.valid_from,
                 pr.channel_id, pr.market_id, pr.entity_id, pr.authorised_price, pr.max_discount_pct,
                 pr.min_qty, pr.up_to_qty, pr.version, pr.tax_regime, tr.rate_percent
          FROM price_rule pr
          JOIN tax_regime tr ON tr.code = pr.tax_regime
          JOIN price_agreement pa ON pa.id = pr.price_agreement_id
          WHERE pr.surface = 'customer' AND pr.product_variant_id = $variantId AND pr.currency = $currency
            AND pr.status = 'active'
            AND pr.effective_from <= $asOf AND (pr.effective_to IS NULL OR pr.effective_to > $asOf)
            AND pa.base_volume_basis <> 'per_order'
            AND pa.status = 'active' AND pa.valid_from <= $asOf AND (pa.valid_to IS NULL OR pa.valid_to > $asOf)
            AND (pa.applies_to = 'open_list'
                 OR (pa.applies_to = 'customer_set' AND $customer IS NOT NULL
                     AND EXISTS (SELECT 1 FROM price_agreement_customer pac
                                 WHERE pac.agreement_id = pa.id AND pac.party_id = $customer))
                 OR (pa.applies_to = 'segment' AND $customer IS NOT NULL
                     AND EXISTS (SELECT 1 FROM party p WHERE p.id = $customer AND p.segment = pa.scope_value))
                 OR (pa.applies_to = 'sector' AND $customer IS NOT NULL
                     AND EXISTS (SELECT 1 FROM party p WHERE p.id = $customer AND p.sector = pa.scope_value)))"""
      .query[CumBandRow]
      .to[List]
      .map(_.map {
        case (id, agr, applies, basis, vf, ch, mk, en, price, disc, minQ, upTo, ver, tr, rate) =>
          CumulativeBand(id, agr, applies, basis, vf, ch, mk, en, price, disc, minQ, upTo, ver, tr, rate)
      })

  def listRulesJson: ConnectionIO[List[Json]] =
    sql"""SELECT pr.id, pr.surface, pr.currency, pr.status, pr.authorised_price, pr.max_discount_pct,
                 pr.tp_method, pr.tp_markup_pct, pr.from_entity_id, pr.to_entity_id,
                 pv.sku, COALESCE(pa.name, 'open'), COALESCE(pa.applies_to, 'open_list'), pr.min_qty
          FROM price_rule pr
          LEFT JOIN product_variant pv ON pv.id = pr.product_variant_id
          LEFT JOIN price_agreement pa ON pa.id = pr.price_agreement_id
          ORDER BY COALESCE(pa.name, 'open'), pv.sku, pr.min_qty"""
      .query[
        (
            UUID,
            String,
            String,
            String,
            BigDecimal,
            BigDecimal,
            Option[String],
            Option[BigDecimal],
            Option[UUID],
            Option[UUID],
            Option[String],
            String,
            String,
            Int
        )
      ]
      .to[List]
      .map(_.map {
        case (id, surface, ccy, status, price, disc, tpm, tpmk, fe, te, sku, agreement, appliesTo, minQty) =>
          Json.obj(
            "id"               -> id.toString.asJson,
            "surface"          -> surface.asJson,
            "currency"         -> ccy.asJson,
            "status"           -> status.asJson,
            "authorised_price" -> price.toString.asJson,
            "max_discount_pct" -> disc.toString.asJson,
            "tp_method"        -> tpm.asJson,
            "tp_markup_pct"    -> tpmk.map(_.toString).asJson,
            "from_entity_id"   -> fe.map(_.toString).asJson,
            "to_entity_id"     -> te.map(_.toString).asJson,
            "sku"              -> sku.asJson,
            "agreement"        -> agreement.asJson,
            "applies_to"       -> appliesTo.asJson,
            "min_qty"          -> minQty.asJson
          )
      })

  def insert(
      surface: String,
      variantId: Option[UUID],
      channel: Option[UUID],
      market: Option[UUID],
      entity: Option[UUID],
      currency: String,
      taxRegime: Option[String],
      authorisedPrice: BigDecimal,
      maxDiscountPct: BigDecimal,
      minQty: Int,
      fromEntity: Option[UUID],
      toEntity: Option[UUID],
      tpMethod: Option[String],
      tpMarkup: Option[BigDecimal],
      owner: Option[UUID]
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO price_rule
            (surface, product_variant_id, channel_id, market_id, entity_id, currency, tax_regime,
             authorised_price, max_discount_pct, min_qty, from_entity_id, to_entity_id, tp_method, tp_markup_pct,
             status, owner_user_id)
          VALUES ($surface, $variantId, $channel, $market, $entity, $currency, $taxRegime,
             $authorisedPrice, $maxDiscountPct, $minQty, $fromEntity, $toEntity, $tpMethod, $tpMarkup,
             'draft', $owner)
          RETURNING id""".query[UUID].unique

  def activate(id: UUID, approvedBy: UUID): ConnectionIO[Int] =
    sql"""UPDATE price_rule SET status = 'active', approved_by = $approvedBy, updated_at = now()
          WHERE id = $id""".update.run

  def logChange(ruleId: UUID, after: Json, actor: UUID): ConnectionIO[Int] =
    sql"""INSERT INTO pricing_change_log (price_rule_id, after, actor, approved_by)
          VALUES ($ruleId, $after, $actor, $actor)""".update.run
}
