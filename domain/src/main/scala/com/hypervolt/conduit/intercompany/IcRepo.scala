package com.hypervolt.conduit.intercompany

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.LocalDate
import java.util.UUID

// Persistence for the intercompany subsystem (doc 13). Reads the topology inputs, the active TP policy, the
// specific lot's landed cost and the FX inputs (hedge/spot); writes the link, tp_documents, the sell/buy legs
// and the stock transfer; and provides the entity-proxy get-or-create helpers the order/PO legs need.

final case class PolicyRow(
    id: UUID,
    version: Int,
    method: String,
    markupPct: Option[BigDecimal],
    resaleMarginPct: Option[BigDecimal],
    fixedPrice: Option[BigDecimal],
    fixedCurrency: Option[String],
    tpCurrency: Option[String],
    roundingBoundary: String,
    documentationMethod: Option[String]
)

final case class LotRow(id: UUID, productVariantId: UUID, qty: Int, landedUnitCost: BigDecimal, currency: String)

final case class HedgeRow(id: UUID, contractedRate: BigDecimal, notional: BigDecimal, notionalUsed: BigDecimal)

object IcRepo {

  def allEntities: ConnectionIO[Map[UUID, EntityNode]] =
    sql"""SELECT id, name, functional_currency, jurisdiction, procurement_parent_id FROM entity"""
      .query[(UUID, String, String, String, Option[UUID])]
      .to[List]
      .map(_.map { case (id, n, fc, j, p) => id -> EntityNode(id, n, fc, j, p) }.toMap)

  def entityNode(id: UUID): ConnectionIO[Option[EntityNode]] =
    sql"""SELECT id, name, functional_currency, jurisdiction, procurement_parent_id FROM entity WHERE id = $id"""
      .query[(UUID, String, String, String, Option[UUID])]
      .option
      .map(_.map { case (i, n, fc, j, p) => EntityNode(i, n, fc, j, p) })

  // The active, scope-matching policy for the hop+variant, most-specific scope + latest version first.
  def activePolicy(from: UUID, to: UUID, variant: UUID, asOf: LocalDate): ConnectionIO[Option[PolicyRow]] =
    sql"""SELECT id, version, method, markup_pct, resale_margin_pct, fixed_price, fixed_currency,
                 tp_currency, rounding_boundary, documentation_method
          FROM transfer_price_policy
          WHERE from_entity_id = $from AND to_entity_id = $to AND status = 'active'
            AND effective_from <= $asOf AND (effective_to IS NULL OR effective_to > $asOf)
            AND ( product_scope = '{}'::jsonb
                  OR product_scope -> 'variant' @> to_jsonb(${variant.toString})
                  OR EXISTS (SELECT 1 FROM product_variant v JOIN product_family f ON f.id = v.family_id
                             WHERE v.id = $variant AND product_scope -> 'family' @> to_jsonb(f.code)) )
          ORDER BY (product_scope <> '{}'::jsonb) DESC, version DESC
          LIMIT 1"""
      .query[PolicyRow]
      .option

  def lot(id: UUID): ConnectionIO[Option[LotRow]] =
    sql"""SELECT id, product_variant_id, qty, landed_unit_cost, currency FROM lot_batch WHERE id = $id"""
      .query[LotRow]
      .option

  // The downstream customer resale price anchor for resale_minus: the active customer price_rule for the variant.
  def resaleAnchor(variant: UUID): ConnectionIO[Option[BigDecimal]] =
    sql"""SELECT authorised_price FROM price_rule
          WHERE product_variant_id = $variant AND surface = 'customer' AND status = 'active'
          ORDER BY version DESC LIMIT 1"""
      .query[BigDecimal]
      .option

  def spotRate(base: String, quote: String, asOf: LocalDate): ConnectionIO[Option[BigDecimal]] =
    sql"""SELECT rate FROM exchange_rate
          WHERE base = $base AND quote = $quote AND rate_type = 'spot' AND as_of <= $asOf
          ORDER BY as_of DESC LIMIT 1"""
      .query[BigDecimal]
      .option

  def activeHedge(
      pairFrom: String,
      pairTo: String,
      entity: UUID,
      asOf: LocalDate,
      need: BigDecimal
  ): ConnectionIO[Option[HedgeRow]] =
    sql"""SELECT id, contracted_rate, notional, notional_used FROM fx_hedge
          WHERE pair_from = $pairFrom AND pair_to = $pairTo AND entity_id = $entity AND status = 'active'
            AND valid_from <= $asOf AND valid_to > $asOf AND (notional - notional_used) >= $need
          ORDER BY valid_from DESC LIMIT 1"""
      .query[HedgeRow]
      .option

  def drawHedge(id: UUID, amount: BigDecimal): ConnectionIO[Int] =
    sql"UPDATE fx_hedge SET notional_used = notional_used + $amount WHERE id = $id".update.run

  // A period is locked for an entity if an accounting_period row for that month is status='locked'.
  def periodLocked(entity: UUID, periodKey: String): ConnectionIO[Boolean] =
    sql"""SELECT EXISTS (SELECT 1 FROM accounting_period
            WHERE entity_id = $entity AND period_key = $periodKey AND status = 'locked')"""
      .query[Boolean]
      .unique

  // ----- entity proxies (the IC sell order / buy PO / stock transfer need a party/supplier/location) -----

  def proxyParty(entity: EntityNode): ConnectionIO[UUID] =
    sql"""SELECT id FROM party WHERE display_name = ${"IC:" + entity.name} LIMIT 1""".query[UUID].option.flatMap {
      case Some(id) => id.pure[ConnectionIO]
      case None =>
        sql"""INSERT INTO party (display_name, party_type, is_organization, default_entity_id)
              VALUES (${"IC:" + entity.name}, 'other', true, ${entity.id}) RETURNING id""".query[UUID].unique
    }

  def proxySupplier(entity: EntityNode): ConnectionIO[UUID] =
    sql"""SELECT id FROM supplier WHERE name = ${"IC:" + entity.name} LIMIT 1""".query[UUID].option.flatMap {
      case Some(id) => id.pure[ConnectionIO]
      case None =>
        sql"""INSERT INTO supplier (name, billing_currency, supplier_entity)
              VALUES (${"IC:" + entity.name}, ${entity.functionalCurrency}, 'intercompany') RETURNING id"""
          .query[UUID]
          .unique
    }

  def proxyLocation(entity: EntityNode): ConnectionIO[UUID] =
    sql"""SELECT id FROM location WHERE entity_id = ${entity.id} ORDER BY created_at LIMIT 1"""
      .query[UUID]
      .option
      .flatMap {
        case Some(id) => id.pure[ConnectionIO]
        case None =>
          sql"""INSERT INTO location (entity_id, code, name, type)
              VALUES (${entity.id}, ${"IC-" + entity.name}, ${entity.name + " (IC)"}, 'warehouse') RETURNING id"""
            .query[UUID]
            .unique
      }
}
