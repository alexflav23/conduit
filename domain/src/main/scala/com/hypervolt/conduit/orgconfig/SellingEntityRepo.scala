package com.hypervolt.conduit.orgconfig

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import java.util.UUID

// The seller-of-record resolution (doc 16 §1.3 / §9): which operating entity books a sale into a jurisdiction, and
// is therefore the registered taxpayer whose VAT control the exposure accrues against. Effective-dated — the
// resolution for any historic `as_of` is reproducible.
object SellingEntityRepo {

  // The active entity for sales into `jurisdiction` at `asOf` (None ⇒ fall back to the order's stamped entity).
  // status <> 'draft' (not '=active'): a superseded row is still the entity in force for an as_of inside its
  // window — the effective dates, not the status, select the historically-correct mapping.
  def active(jurisdiction: String, asOf: LocalDate): ConnectionIO[Option[UUID]] =
    sql"""SELECT entity_id FROM selling_entity
          WHERE jurisdiction = $jurisdiction AND status <> 'draft'
            AND effective_from <= $asOf AND (effective_to IS NULL OR effective_to > $asOf)
          ORDER BY effective_from DESC
          LIMIT 1"""
      .query[UUID]
      .option

  def list: ConnectionIO[List[Json]] =
    sql"""SELECT se.id, se.jurisdiction, se.entity_id, e.name, e.functional_currency, se.effective_from,
            se.effective_to, se.status
          FROM selling_entity se JOIN entity e ON e.id = se.entity_id
          ORDER BY se.jurisdiction, se.effective_from DESC"""
      .query[(UUID, String, UUID, String, String, LocalDate, Option[LocalDate], String)]
      .to[List]
      .map(_.map {
        case (id, jur, eid, name, ccy, from, to, status) =>
          Json.obj(
            "id"                  -> id.toString.asJson,
            "jurisdiction"        -> jur.asJson,
            "entity_id"           -> eid.toString.asJson,
            "entity_name"         -> name.asJson,
            "functional_currency" -> ccy.asJson,
            "effective_from"      -> from.toString.asJson,
            "effective_to"        -> to.map(_.toString).asJson,
            "status"              -> status.asJson
          )
      })
}
