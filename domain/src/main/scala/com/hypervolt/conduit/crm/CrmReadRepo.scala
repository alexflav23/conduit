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

  // The attributed deal/PO book (doc 26 §4a + the HubSpot company association): every historical customer deal
  // tied to the installer/wholesaler/retail company that placed it. Paginated; filterable by segment + won and a
  // company/pipeline search. company_name is the attribution carried on the deal.
  private def dealFilters(segment: Option[String], won: Option[Boolean], q: Option[String]): Option[doobie.Fragment] = {
    val fs = List(
      segment.map(s => fr"segment = $s"),
      won.map(w => fr"is_won = $w"),
      q.map(t => fr"(company_name ILIKE ${"%" + t + "%"} OR pipeline ILIKE ${"%" + t + "%"})")
    ).flatten
    if (fs.isEmpty) None else Some(fs.reduce(_ ++ fr"AND" ++ _))
  }

  def deals(
      segment: Option[String],
      won: Option[Boolean],
      q: Option[String],
      limit: Int,
      offset: Int
  ): doobie.ConnectionIO[List[Json]] =
    (fr"""SELECT deal_id, company_name, segment, pipeline, amount, created_at, closed_at, is_won, is_closed
          FROM deal_snapshot"""
      ++ Fragments.whereAndOpt(dealFilters(segment, won, q))
      ++ fr"ORDER BY created_at DESC NULLS LAST, amount DESC NULLS LAST LIMIT $limit OFFSET $offset")
      .query[
        (String, Option[String], Option[String], Option[String], Option[BigDecimal], Option[LocalDate], Option[LocalDate], Boolean, Boolean)
      ]
      .to[List]
      .map(_.map {
        case (id, company, seg, pipe, amount, created, closed, won_, isClosed) =>
          Json.obj(
            "deal_id"      -> id.asJson,
            "company_name" -> company.asJson,
            "segment"      -> seg.asJson,
            "pipeline"     -> pipe.asJson,
            "amount"       -> amount.map(_.toString).asJson,
            "created_at"   -> created.map(_.toString).asJson,
            "closed_at"    -> closed.map(_.toString).asJson,
            "won"          -> won_.asJson,
            "is_closed"    -> isClosed.asJson,
            "status"       -> (if (!isClosed) "open" else if (won_) "won" else "lost").asJson
          )
      })

  def dealsCount(segment: Option[String], won: Option[Boolean], q: Option[String]): doobie.ConnectionIO[Long] =
    (fr"SELECT count(*) FROM deal_snapshot" ++ Fragments.whereAndOpt(dealFilters(segment, won, q)))
      .query[Long]
      .unique

  def dealsSummary: doobie.ConnectionIO[Json] =
    sql"""SELECT COALESCE(segment, 'unattributed'), count(*), count(*) FILTER (WHERE is_won),
                 COALESCE(sum(amount) FILTER (WHERE is_won), 0), count(*) FILTER (WHERE company_id IS NOT NULL)
          FROM deal_snapshot GROUP BY 1 ORDER BY 2 DESC"""
      .query[(String, Long, Long, BigDecimal, Long)]
      .to[List]
      .map(rs =>
        Json.fromValues(rs.map {
          case (seg, n, wonN, wonValue, attributed) =>
            Json.obj(
              "segment"        -> seg.asJson,
              "deals"          -> n.asJson,
              "won"            -> wonN.asJson,
              "won_value"      -> wonValue.toString.asJson,
              "attributed"     -> attributed.asJson
            )
        })
      )
}
