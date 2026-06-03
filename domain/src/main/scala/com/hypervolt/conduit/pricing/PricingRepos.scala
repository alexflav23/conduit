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
}

object PriceRuleRepo {

  private type CandidateRow =
    (UUID, Option[UUID], Option[UUID], Option[UUID], BigDecimal, BigDecimal, Int, Int, String, BigDecimal)

  def candidates(
      variantId: UUID,
      channel: UUID,
      market: UUID,
      entity: Option[UUID],
      currency: String,
      qty: Int,
      asOf: Instant
  ): ConnectionIO[List[PriceRuleCandidate]] =
    sql"""SELECT pr.id, pr.channel_id, pr.market_id, pr.entity_id, pr.authorised_price, pr.max_discount_pct,
                 pr.min_qty, pr.version, pr.tax_regime, tr.rate_percent
          FROM price_rule pr JOIN tax_regime tr ON tr.code = pr.tax_regime
          WHERE pr.surface = 'customer' AND pr.product_variant_id = $variantId AND pr.currency = $currency
            AND pr.status = 'active'
            AND pr.effective_from <= $asOf AND (pr.effective_to IS NULL OR pr.effective_to > $asOf)
            AND (pr.channel_id = $channel OR pr.channel_id IS NULL)
            AND (pr.market_id = $market OR pr.market_id IS NULL)
            AND (pr.entity_id = $entity OR pr.entity_id IS NULL)
            AND pr.min_qty <= $qty"""
      .query[CandidateRow]
      .to[List]
      .map(_.map { case (id, ch, mk, en, price, disc, minQ, ver, tr, rate) =>
        PriceRuleCandidate(id, ch, mk, en, price, disc, minQ, ver, tr, rate)
      })

  def listRulesJson: ConnectionIO[List[Json]] =
    sql"""SELECT id, surface, currency, status, authorised_price, max_discount_pct,
                 tp_method, tp_markup_pct, from_entity_id, to_entity_id
          FROM price_rule ORDER BY created_at DESC"""
      .query[
        (UUID, String, String, String, BigDecimal, BigDecimal, Option[String], Option[BigDecimal], Option[UUID], Option[UUID])
      ]
      .to[List]
      .map(_.map { case (id, surface, ccy, status, price, disc, tpm, tpmk, fe, te) =>
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
          "to_entity_id"     -> te.map(_.toString).asJson
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
