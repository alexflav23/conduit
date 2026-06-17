package com.hypervolt.conduit.crm

import cats.syntax.all._
import doobie.Fragments
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
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
  private def dealFilters(
      segment: Option[String],
      pipeline: Option[String],
      status: Option[String],
      q: Option[String]
  ): Option[doobie.Fragment] = {
    val statusFr = status.collect {
      case "won"  => fr"is_won = true"
      case "lost" => fr"is_closed = true AND is_won = false"
      case "open" => fr"is_closed = false"
    }
    val fs = List(
      segment.map(s => fr"segment = $s"),
      pipeline.map(p => fr"pipeline = $p"),
      statusFr,
      q.map(t => fr"(company_name ILIKE ${"%" + t + "%"} OR pipeline ILIKE ${"%" + t + "%"})")
    ).flatten
    if (fs.isEmpty) None else Some(fs.reduce(_ ++ fr"AND" ++ _))
  }

  // Sort is a fixed whitelist (never interpolated) so it is injection-safe.
  private def dealSort(sort: Option[String], dir: Option[String]): doobie.Fragment = {
    val d = if (dir.contains("asc")) fr"ASC" else fr"DESC"
    sort match {
      case Some("amount")  => fr"ORDER BY amount" ++ d ++ fr"NULLS LAST"
      case Some("company") => fr"ORDER BY company_name" ++ d ++ fr"NULLS LAST"
      case Some("closed")  => fr"ORDER BY closed_at" ++ d ++ fr"NULLS LAST"
      case _               => fr"ORDER BY created_at" ++ d ++ fr"NULLS LAST"
    }
  }

  def deals(
      segment: Option[String],
      pipeline: Option[String],
      status: Option[String],
      q: Option[String],
      sort: Option[String],
      dir: Option[String],
      limit: Int,
      offset: Int
  ): doobie.ConnectionIO[List[Json]] =
    (fr"""SELECT deal_id, company_name, segment, pipeline, amount, created_at, closed_at, is_won, is_closed
          FROM deal_snapshot"""
      ++ Fragments.whereAndOpt(dealFilters(segment, pipeline, status, q))
      ++ dealSort(sort, dir) ++ fr", deal_id LIMIT $limit OFFSET $offset")
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

  def dealsCount(
      segment: Option[String],
      pipeline: Option[String],
      status: Option[String],
      q: Option[String]
  ): doobie.ConnectionIO[Long] =
    (fr"SELECT count(*) FROM deal_snapshot" ++ Fragments.whereAndOpt(dealFilters(segment, pipeline, status, q)))
      .query[Long]
      .unique

  private def rollup(col: doobie.Fragment): doobie.ConnectionIO[List[Json]] =
    (fr"SELECT" ++ col ++ fr""", segment, count(*), count(*) FILTER (WHERE is_won),
          COALESCE(sum(amount) FILTER (WHERE is_won), 0), count(*) FILTER (WHERE company_id IS NOT NULL)
          FROM deal_snapshot GROUP BY 1, 2 ORDER BY 3 DESC""")
      .query[(Option[String], Option[String], Long, Long, BigDecimal, Long)]
      .to[List]
      .map(_.map {
        case (key, seg, n, wonN, wonValue, attributed) =>
          Json.obj(
            "key"        -> key.getOrElse("unattributed").asJson,
            "segment"    -> seg.asJson,
            "deals"      -> n.asJson,
            "won"        -> wonN.asJson,
            "won_value"  -> wonValue.toString.asJson,
            "attributed" -> attributed.asJson
          )
      })

  def dealsSummary: doobie.ConnectionIO[Json] =
    (rollup(fr"COALESCE(segment, 'unattributed')"), rollup(fr"COALESCE(pipeline, 'unknown')")).tupled
      .map {
        case (segments, pipelines) =>
          Json.obj("segments" -> Json.fromValues(segments), "pipelines" -> Json.fromValues(pipelines))
      }

  // ---- Master accounts (doc 02 golden record): the Conduit entity, with MRPeasy + HubSpot + contacts as parts ----

  private val nameExpr = fr"regexp_replace(p.display_name, '^MRP:\s*', '')"

  private def accountWhere(segment: Option[String], q: Option[String]): doobie.Fragment =
    fr"WHERE " ++ List(
      Some(fr"p.parent_party_id IS NULL"),
      Some(fr"p.status <> 'merged'"),
      segment.map(s => fr"p.segment = $s"),
      q.map { t =>
        val digits = t.filter(_.isDigit)
        val phoneClause =
          if (digits.length >= 5)
            fr" OR EXISTS (SELECT 1 FROM contact c WHERE c.party_id = p.id AND regexp_replace(c.phone, '[^0-9]', '', 'g') ILIKE ${"%" + digits + "%"})"
          else fr""
        fr"(p.display_name ILIKE ${"%" + t + "%"} OR p.external_refs->>'owner_email' ILIKE ${"%" + t + "%"}" ++
          fr" OR EXISTS (SELECT 1 FROM contact c WHERE c.party_id = p.id AND c.email ILIKE ${"%" + t + "%"})" ++
          phoneClause ++ fr")"
      }
    ).flatten.reduce(_ ++ fr" AND " ++ _)

  def listAccounts(segment: Option[String], q: Option[String], limit: Int, offset: Int): doobie.ConnectionIO[List[Json]] =
    (fr"""SELECT jsonb_build_object(
            'id', p.id::text, 'name', """ ++ nameExpr ++ fr""", 'segment', p.segment, 'type', p.party_type,
            'first_name', (SELECT c.first_name FROM contact c WHERE c.party_id = p.id AND c.first_name IS NOT NULL ORDER BY c.is_primary DESC LIMIT 1),
            'last_name', (SELECT c.last_name FROM contact c WHERE c.party_id = p.id AND c.last_name IS NOT NULL ORDER BY c.is_primary DESC LIMIT 1),
            'email', COALESCE(p.external_refs->>'owner_email', (SELECT c.email::text FROM contact c WHERE c.party_id = p.id AND c.email IS NOT NULL ORDER BY c.is_primary DESC LIMIT 1)),
            'phone', (SELECT c.phone FROM contact c WHERE c.party_id = p.id AND c.phone IS NOT NULL ORDER BY c.is_primary DESC LIMIT 1),
            'mrpeasy', (SELECT count(*) FROM account_source_link a WHERE a.party_id = p.id AND a.source_system = 'mrpeasy'),
            'hubspot_companies', (SELECT count(*) FROM account_source_link a WHERE a.party_id = p.id AND a.source_system = 'hubspot_company'),
            'contacts', (SELECT count(*) FROM contact c WHERE c.party_id = p.id),
            'branches', (SELECT count(*) FROM party b WHERE b.parent_party_id = p.id),
            'orders', (SELECT count(*) FROM "order" o WHERE o.sold_to_party_id = p.id),
            'order_value', (SELECT COALESCE(sum(total_inc_vat),0) FROM "order" o WHERE o.sold_to_party_id = p.id))
          FROM party p """
      ++ accountWhere(segment, q)
      ++ fr"""ORDER BY (SELECT count(*) FROM "order" o WHERE o.sold_to_party_id = p.id) DESC, p.display_name LIMIT $limit OFFSET $offset""")
      .query[Json]
      .to[List]

  def countAccounts(segment: Option[String], q: Option[String]): doobie.ConnectionIO[Long] =
    (fr"SELECT count(*) FROM party p " ++ accountWhere(segment, q)).query[Long].unique

  def accountDetail(id: UUID): doobie.ConnectionIO[Option[Json]] =
    sql"""SELECT jsonb_build_object(
            'id', p.id::text, 'name', regexp_replace(p.display_name, '^MRP:\s*', ''), 'segment', p.segment,
            'type', p.party_type, 'external_refs', p.external_refs,
            'parent', (SELECT jsonb_build_object('id', pp.id::text, 'name', regexp_replace(pp.display_name,'^MRP:\s*',''))
                       FROM party pp WHERE pp.id = p.parent_party_id),
            'sold_via', (SELECT jsonb_build_object('id', sv.id::text, 'name', regexp_replace(sv.display_name,'^MRP:\s*',''),
                          'match', p.external_refs->>'sold_via_match')
                         FROM party sv WHERE sv.id = (p.external_refs->>'sold_via_party_id')::uuid AND sv.status <> 'merged'),
            'sources', (SELECT COALESCE(jsonb_agg(jsonb_build_object('system', a.source_system, 'source_id', a.source_id,
                          'name', a.source_name, 'method', a.match_method, 'confidence', a.confidence) ORDER BY a.source_system), '[]'::jsonb)
                        FROM account_source_link a WHERE a.party_id = p.id),
            'contacts', (SELECT COALESCE(jsonb_agg(jsonb_build_object('name', btrim(coalesce(c.first_name,'')||' '||coalesce(c.last_name,'')),
                          'email', c.email::text, 'phone', c.phone, 'role', c.role,
                          'entity_type', CASE WHEN EXISTS (SELECT 1 FROM party op WHERE op.party_type='individual'
                                          AND lower(op.external_refs->>'owner_email') = lower(c.email::text)) THEN 'end_customer' ELSE 'contact' END)
                          ORDER BY c.is_primary DESC, c.last_name), '[]'::jsonb)
                         FROM contact c WHERE c.party_id = p.id),
            'branches', (SELECT COALESCE(jsonb_agg(jsonb_build_object('id', b.id::text, 'name', regexp_replace(b.display_name,'^MRP:\s*',''),
                          'orders', (SELECT count(*) FROM "order" o WHERE o.sold_to_party_id = b.id)) ORDER BY b.display_name), '[]'::jsonb)
                         FROM party b WHERE b.parent_party_id = p.id),
            'orders', (SELECT COALESCE(jsonb_agg(t.o ORDER BY t.d DESC), '[]'::jsonb) FROM (
                         SELECT jsonb_build_object('order_no', o.order_no, 'date', o.created_at::date::text, 'total', o.total_inc_vat) AS o,
                                o.created_at AS d
                         FROM "order" o WHERE o.sold_to_party_id = p.id ORDER BY o.created_at DESC LIMIT 25) t),
            'chargers', (SELECT COALESCE(jsonb_agg(jsonb_build_object(
                          'id', s.id::text, 'serial', s.serial_no, 'sku', pv.sku, 'status', s.status,
                          'activated_at', s.activated_at, 'warranty_end', s.warranty_end,
                          'warranty_days_left', GREATEST(0, (s.warranty_end - current_date)),
                          'replaces', (SELECT r.serial_no FROM serial_unit r WHERE r.id = s.replaces_serial_unit_id),
                          'replaced_by', (SELECT jsonb_agg(c.serial_no) FROM serial_unit c WHERE c.replaces_serial_unit_id = s.id))
                          ORDER BY s.activated_at DESC NULLS LAST), '[]'::jsonb)
                         FROM serial_unit s LEFT JOIN product_variant pv ON pv.id = s.product_variant_id
                         WHERE s.owner_party_id = p.id))
          FROM party p WHERE p.id = $id""".query[Json].option
}
