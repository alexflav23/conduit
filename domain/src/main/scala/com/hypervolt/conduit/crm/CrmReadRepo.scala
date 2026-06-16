package com.hypervolt.conduit.crm

import doobie.Fragments
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// CRM reads (spec/ui/22-crm.md): the scope-filtered party worklist and the deal pipeline that back the desk.
// Identity-only fields here (the `volume` layer) — credit limit (`commercial`) and contacts (`pii`) stay behind
// the existing per-party credit-terms route, so this list carries nothing layer-walled.
object CrmReadRepo {

  // segment carries the real customer classification (installers/wholesale/energy/online_retail); party.sector is
  // unset in the MRP import, so the sector axis falls back to segment. market resolves to its code (e.g. "UK"),
  // never a raw UUID.
  private val partyCols =
    fr"""SELECT p.id, p.display_name, p.legal_name, p.party_type,
                COALESCE(p.sector, p.segment), p.segment, p.status,
                m.code, p.channel_id, p.account_manager_user_id, p.created_at
         FROM party p LEFT JOIN market m ON m.id = p.market_id"""

  def listParties(
      market: Option[UUID],
      sector: Option[String],
      q: Option[String],
      limit: Int
  ): doobie.ConnectionIO[List[Json]] =
    (partyCols ++ Fragments.whereAndOpt(
      market.map(mk => fr"p.market_id = $mk"),
      sector.map(s => fr"COALESCE(p.sector, p.segment) = $s"),
      q.map(term => fr"p.display_name ILIKE ${"%" + term + "%"}")
    ) ++ fr"ORDER BY p.display_name LIMIT $limit")
      .query[
        (
            UUID,
            String,
            Option[String],
            String,
            Option[String],
            Option[String],
            String,
            Option[String],
            Option[UUID],
            Option[UUID],
            Instant
        )
      ]
      .to[List]
      .map(_.map {
        case (id, name, legal, ptype, sec, seg, status, mkt, ch, mgr, created) =>
          Json.obj(
            "id"              -> id.toString.asJson,
            "display_name"    -> name.asJson,
            "legal_name"      -> legal.asJson,
            "party_type"      -> ptype.asJson,
            "sector"          -> sec.asJson,
            "segment"         -> seg.asJson,
            "status"          -> status.asJson,
            "market"          -> mkt.asJson,
            "channel"         -> ch.map(_.toString).asJson,
            "account_manager" -> mgr.map(_.toString).asJson,
            "created_at"      -> created.toString.asJson
          )
      })

  def sectors: doobie.ConnectionIO[List[String]] =
    sql"""SELECT DISTINCT COALESCE(sector, segment) FROM party
          WHERE COALESCE(sector, segment) IS NOT NULL AND COALESCE(sector, segment) <> '' ORDER BY 1"""
      .query[String]
      .to[List]

  // deal_snapshot is the HubSpot deal register: no granular stage column, so the board derives open/won/lost from
  // the closed/won flags. The open deals are the live pipeline; closed are kept for the weighted-value baseline.
  def pipeline(limit: Int): doobie.ConnectionIO[Json] = {
    val rows =
      sql"""SELECT deal_id, pipeline, amount, created_at, closed_at, is_won, is_closed
            FROM deal_snapshot
            ORDER BY is_closed ASC, amount DESC NULLS LAST
            LIMIT $limit"""
        .query[(String, Option[String], Option[BigDecimal], Option[LocalDate], Option[LocalDate], Boolean, Boolean)]
        .to[List]
    rows.map { rs =>
      val deals = rs.map {
        case (id, pipe, amount, created, _, won, closed) =>
          val stage = if (!closed) "open" else if (won) "won" else "lost"
          Json.obj(
            "id"         -> id.asJson,
            "party_name" -> pipe.getOrElse("Deal").asJson,
            "stage"      -> stage.asJson,
            "value"      -> amount.map(_.toString).asJson,
            "age"        -> created.map(_.toString).asJson
          )
      }
      Json.obj(
        "stages" -> List("open", "won", "lost").asJson,
        "deals"  -> Json.fromValues(deals)
      )
    }
  }
}
