package com.hypervolt.conduit.intercompany

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.util.UUID

// The central price catalogue (spec doc 28 §2.1): the principal SETS a per-market price list for internal
// sales to operating entities. Governed like every price in Conduit (doc 24): append-only versions,
// maker <> checker, effective-dated. Resolution precedence: active catalogue line -> transfer_price_policy
// formula -> FAIL CLOSED (an unpriced internal hop is a governance error, never a silent landed-cost pass).
object ProcurementCatalogue {

  final case class PriceListLine(variantId: UUID, unitPrice: BigDecimal)

  def propose(
      procurementEntity: UUID,
      market: UUID,
      currency: String,
      lines: List[PriceListLine],
      maker: UUID
  ): ConnectionIO[Either[String, UUID]] =
    if (lines.isEmpty) "a price list needs at least one line".asLeft[UUID].pure[ConnectionIO]
    else
      for {
        version <-
          sql"""SELECT COALESCE(MAX(version), 0) + 1 FROM transfer_price_list
                         WHERE procurement_entity_id = $procurementEntity AND market_id = $market"""
            .query[Int]
            .unique
        id <- sql"""INSERT INTO transfer_price_list
                      (procurement_entity_id, market_id, currency, version, proposed_by)
                    VALUES ($procurementEntity, $market, $currency, $version, $maker)
                    RETURNING id""".query[UUID].unique
        _ <- lines.traverse_(l =>
          sql"""INSERT INTO transfer_price_list_line (price_list_id, product_variant_id, unit_price)
                VALUES ($id, ${l.variantId}, ${l.unitPrice})""".update.run
        )
      } yield id.asRight[String]

  // Maker-checker: the proposer cannot activate their own list; activation supersedes the previous version.
  def activate(listId: UUID, checker: UUID): ConnectionIO[Either[String, Unit]] =
    sql"SELECT proposed_by, procurement_entity_id, market_id, status FROM transfer_price_list WHERE id = $listId"
      .query[(Option[UUID], UUID, UUID, String)]
      .option
      .flatMap {
        case None => "unknown price list".asLeft[Unit].pure[ConnectionIO]
        case Some((_, _, _, status)) if status != "draft" =>
          s"price list is $status, not draft".asLeft[Unit].pure[ConnectionIO]
        case Some((maker, _, _, _)) if maker.contains(checker) =>
          "maker-checker: the proposer cannot activate their own price list".asLeft[Unit].pure[ConnectionIO]
        case Some((_, pe, market, _)) =>
          sql"""UPDATE transfer_price_list SET status = 'superseded', effective_to = now(), updated_at = now()
                WHERE procurement_entity_id = $pe AND market_id = $market AND status = 'active'""".update.run *>
            sql"""UPDATE transfer_price_list SET status = 'active', approved_by = $checker, updated_at = now()
                  WHERE id = $listId""".update.run.as(().asRight[String])
      }

  // The one resolver every internal pricing site uses: the active catalogue line as-of the instant.
  def resolve(
      procurementEntity: UUID,
      market: UUID,
      variant: UUID,
      asOf: java.time.LocalDate
  ): ConnectionIO[Option[(UUID, BigDecimal, String)]] =
    sql"""SELECT l.id, ll.unit_price, l.currency
          FROM transfer_price_list l
          JOIN transfer_price_list_line ll ON ll.price_list_id = l.id
          WHERE l.procurement_entity_id = $procurementEntity AND l.market_id = $market
            AND ll.product_variant_id = $variant AND l.status = 'active'
            AND l.effective_from::date <= $asOf AND (l.effective_to IS NULL OR l.effective_to::date >= $asOf)
          ORDER BY l.effective_from DESC LIMIT 1"""
      .query[(UUID, BigDecimal, String)]
      .option
}
