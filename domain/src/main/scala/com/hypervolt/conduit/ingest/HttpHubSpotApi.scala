package com.hypervolt.conduit.ingest

import cats.effect.Async
import cats.syntax.all._
import io.circe.Json
import io.circe.syntax._
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.headers.Authorization

// The live HubSpot CRM v3 implementation of the HubSpotApi seam (S2.1/S2.2, spec 37 §1). Uses the search endpoint
// so a warm pull fetches only rows with hs_lastmodifieddate >= the cursor — the watermark the IngestRunner
// advances. CRUCIALLY it NORMALIZES each v3 object into the canonical record the shared SnapshotLoader handler
// consumes (the boot-ndjson shape), keeping `id` (sourceId) + properties.hs_lastmodifieddate (watermark) so the
// connector + its test, and the live+boot mapping path, are unchanged. Per spec 37 the field map is owned here.
//
// counterparties: companies → party (org), contacts → contact.
// counterparty RELATIONSHIPS: deals are attributed to their company via the v4 batch-associations read
// (search carries no associations), and the pipeline ID is resolved to its label → the deal hangs off the
// master account as a related lifecycle entity (deal_snapshot), exactly as the boot import does.
final class HttpHubSpotApi[F[_]: Async](client: Client[F], token: String, baseUrl: String) extends HubSpotApi[F] {

  private val auth = Authorization(Credentials.Token(AuthScheme.Bearer, token))

  def get(objectType: String, modifiedSince: Option[String]): F[Json] =
    objectType match {
      case "companies" | "contacts" => searchObjects(objectType, modifiedSince)
      case "deals"                  => getDeals(modifiedSince)
      case "line_items"             => getLineItems(modifiedSince)
      case "tickets"                => getTickets(modifiedSince)
      case other =>
        Async[F].raiseError(
          new IllegalArgumentException(
            s"hubspot live pull not wired for '$other' (S2: companies, contacts, deals, line_items)"
          )
        )
    }

  // ── companies + contacts: one search call, normalize each row to the canonical shape ───────────────────
  private val companiesSpec: (List[String], (String, Json) => Json) = (
    List("name", "domain", "industry", "country", "hs_lastmodifieddate"),
    (id, p) =>
      canonical(id, p)(
        "company_id" -> id.asJson,
        "name"       -> str(p, "name"),
        "domain"     -> str(p, "domain"),
        "industry"   -> str(p, "industry"),
        "country"    -> str(p, "country")
      )
  )

  private val contactsSpec: (List[String], (String, Json) => Json) = (
    List(
      "email",
      "firstname",
      "lastname",
      "phone",
      "jobtitle",
      "lifecyclestage",
      "createdate",
      "associatedcompanyid",
      "company",
      "hs_lastmodifieddate"
    ),
    (id, p) =>
      canonical(id, p)(
        "contact_id" -> id.asJson,
        "email"      -> str(p, "email"),
        "first_name" -> str(p, "firstname"),
        "last_name"  -> str(p, "lastname"),
        "phone"      -> str(p, "phone"),
        "company"    -> str(p, "company"),
        "company_id" -> str(p, "associatedcompanyid"),
        "job_title"  -> str(p, "jobtitle"),
        "lifecycle"  -> str(p, "lifecyclestage"),
        "created"    -> dateStr(p.hcursor, "createdate")
      )
  )

  private val objectSpecs: Map[String, (List[String], (String, Json) => Json)] =
    Map("companies" -> companiesSpec, "contacts" -> contactsSpec)

  private def searchObjects(objectType: String, since: Option[String]): F[Json] = {
    val (props, normalize) = objectSpecs(objectType)
    post(s"/crm/v3/objects/$objectType/search", searchBody(props, since)).map { raw =>
      val rows = raw.hcursor.downField("results").values.toList.flatten
      val out = rows.flatMap { row =>
        val c = row.hcursor
        (c.get[String]("id").toOption, c.downField("properties").focus).mapN((id, p) => normalize(id, p))
      }
      Json.obj("results" -> Json.fromValues(out), "paging" -> paging(raw))
    }
  }

  // ── deals: search (watermark) + v4 company association + pipeline-label resolution → deal_snapshot shape ──
  private val dealProps =
    List(
      "dealname",
      "amount",
      "dealstage",
      "pipeline",
      "createdate",
      "closedate",
      "hs_is_closed_won",
      "hs_is_closed",
      "hs_lastmodifieddate"
    )

  private def getDeals(since: Option[String]): F[Json] =
    post("/crm/v3/objects/deals/search", searchBody(dealProps, since)).flatMap { raw =>
      val rows = raw.hcursor.downField("results").values.toList.flatten
      val ids  = rows.flatMap(_.hcursor.get[String]("id").toOption)
      (pipelineLabels, dealCompanyMap(ids)).tupled.map {
        case (labels, companies) =>
          val out = rows.flatMap(r => normDeal(r, labels, companies))
          Json.obj("results" -> Json.fromValues(out), "paging" -> paging(raw))
      }
    }

  private def normDeal(row: Json, labels: Map[String, String], companies: Map[String, String]): Option[Json] = {
    val c = row.hcursor
    c.get[String]("id").toOption.map { id =>
      val p     = c.downField("properties")
      val pid   = p.get[String]("pipeline").toOption.filter(_.nonEmpty)
      val label = pid.flatMap(labels.get).orElse(pid).getOrElse("")
      Json.fromFields(
        List(
          "id"         -> Json.fromString(id),
          "deal_id"    -> Json.fromString(id),
          "created"    -> dateStr(p, "createdate"),
          "closed"     -> dateStr(p, "closedate"),
          "pipeline"   -> Json.fromString(label),
          "won"        -> boolOf(p, "hs_is_closed_won"),
          "is_closed"  -> boolOf(p, "hs_is_closed"),
          "amount"     -> str(p, "amount"),
          "company_id" -> companies.get(id).fold(Json.Null)(Json.fromString),
          "segment"    -> segmentOf(label),
          "properties" -> Json.obj("hs_lastmodifieddate" -> str(p, "hs_lastmodifieddate"))
        )
      )
    }
  }

  // ── line_items: search + line_item→deal association → deal_line shape (a deal's product breakdown) ────────
  private val lineItemProps =
    List("name", "quantity", "price", "amount", "hs_sku", "hs_product_id", "hs_lastmodifieddate")

  private def getLineItems(since: Option[String]): F[Json] =
    post("/crm/v3/objects/line_items/search", searchBody(lineItemProps, since)).flatMap { raw =>
      val rows = raw.hcursor.downField("results").values.toList.flatten
      val ids  = rows.flatMap(_.hcursor.get[String]("id").toOption)
      batchAssoc("line_items", "deals", ids).map { deals =>
        val out = rows.flatMap { row =>
          val c = row.hcursor
          c.get[String]("id").toOption.map { id =>
            val p = c.downField("properties")
            Json.obj(
              "id"           -> Json.fromString(id),
              "line_item_id" -> Json.fromString(id),
              "deal_id"      -> deals.get(id).fold(Json.Null)(Json.fromString),
              "sku"          -> str(p, "hs_sku"),
              "name"         -> str(p, "name"),
              "qty"          -> str(p, "quantity"),
              "unit_price"   -> str(p, "price"),
              "amount"       -> str(p, "amount"),
              "properties"   -> Json.obj("hs_lastmodifieddate" -> str(p, "hs_lastmodifieddate"))
            )
          }
        }
        Json.obj("results" -> Json.fromValues(out), "paging" -> paging(raw))
      }
    }

  // ── tickets: search + company/contact association → support_ticket shape (the service queue) ─────────────
  private val ticketProps =
    List("subject", "content", "hs_pipeline_stage", "hs_ticket_priority", "createdate", "hs_lastmodifieddate")

  private def getTickets(since: Option[String]): F[Json] =
    post("/crm/v3/objects/tickets/search", searchBody(ticketProps, since)).flatMap { raw =>
      val rows = raw.hcursor.downField("results").values.toList.flatten
      val ids  = rows.flatMap(_.hcursor.get[String]("id").toOption)
      (batchAssoc("tickets", "companies", ids), batchAssoc("tickets", "contacts", ids)).tupled.map {
        case (companies, contacts) =>
          val out = rows.flatMap { row =>
            val c = row.hcursor
            c.get[String]("id").toOption.map { id =>
              val p = c.downField("properties")
              Json.obj(
                "id"         -> Json.fromString(id),
                "ticket_ref" -> Json.fromString(id),
                "subject"    -> str(p, "subject"),
                "status"     -> str(p, "hs_pipeline_stage"),
                "priority"   -> str(p, "hs_ticket_priority"),
                "opened_at"  -> dateStr(p, "createdate"),
                "company_id" -> companies.get(id).fold(Json.Null)(Json.fromString),
                "contact_id" -> contacts.get(id).fold(Json.Null)(Json.fromString),
                "properties" -> Json.obj("hs_lastmodifieddate" -> str(p, "hs_lastmodifieddate"))
              )
            }
          }
          Json.obj("results" -> Json.fromValues(out), "paging" -> paging(raw))
      }
    }

  // GET the deal pipelines once per pull → id -> human label (the boot ndjson stored labels, not ids).
  private def pipelineLabels: F[Map[String, String]] =
    getJson("/crm/v3/pipelines/deals")
      .map { raw =>
        raw.hcursor
          .downField("results")
          .values
          .toList
          .flatten
          .flatMap { pl =>
            (pl.hcursor.get[String]("id").toOption, pl.hcursor.get[String]("label").toOption).mapN(_ -> _)
          }
          .toMap
      }
      .handleError(_ => Map.empty)

  private def dealCompanyMap(ids: List[String]): F[Map[String, String]] = batchAssoc("deals", "companies", ids)

  // v4 batch-associations: fromId -> its associated toId (prefer the Primary association). Search carries none.
  private def batchAssoc(fromType: String, toType: String, ids: List[String]): F[Map[String, String]] =
    if (ids.isEmpty) Async[F].pure(Map.empty)
    else
      post(
        s"/crm/v4/associations/$fromType/$toType/batch/read",
        Json.obj("inputs" -> Json.fromValues(ids.map(id => Json.obj("id" -> id.asJson))))
      ).map { raw =>
        raw.hcursor
          .downField("results")
          .values
          .toList
          .flatten
          .flatMap { r =>
            val from = r.hcursor.downField("from").get[String]("id").toOption
            val tos  = r.hcursor.downField("to").values.toList.flatten
            val primary = tos
              .find(
                _.hcursor
                  .downField("associationTypes")
                  .values
                  .toList
                  .flatten
                  .exists(_.hcursor.get[String]("label").toOption.contains("Primary"))
              )
              .orElse(tos.headOption)
            (from, primary.flatMap(t => t.hcursor.get[Long]("toObjectId").toOption.map(_.toString))).mapN(_ -> _)
          }
          .toMap
      }.handleError(_ => Map.empty)

  // installer / retail / wholesale / automotive / international, derived from the pipeline label.
  private def segmentOf(label: String): Json = {
    val l = label.toLowerCase
    if (l.contains("installer")) Json.fromString("installer")
    else if (l.contains("retail")) Json.fromString("online_retail")
    else if (l.contains("distributor") || l.contains("wholesaler")) Json.fromString("wholesale")
    else if (l.contains("automotive")) Json.fromString("automotive")
    else if (l.contains("international")) Json.fromString("international")
    else Json.Null
  }

  // ── company hierarchy (wholesale branches): authoritative HubSpot parent/child company links ─────────────
  // (childCompanyId, parentCompanyId) pairs across ALL companies. HubSpot paginates companies properly via
  // `after`, so this is complete (maxPages caps it). Derived from both directions: "Child Company" (from = the
  // parent) and "Parent Company" (from = the child). Consumed by BranchLinkService to set party.parent_party_id.
  def companyParentPairs(maxPages: Int = 300): F[List[(String, String)]] = {
    def pageIds(after: Option[String], acc: List[String], n: Int): F[List[String]] =
      if (n >= maxPages) Async[F].pure(acc)
      else
        getJson("/crm/v3/objects/companies?limit=100" + after.fold("")("&after=" + _)).flatMap { d =>
          val ids = d.hcursor.downField("results").values.toList.flatten.flatMap(_.hcursor.get[String]("id").toOption)
          d.hcursor.downField("paging").downField("next").get[String]("after").toOption match {
            case Some(nx) => pageIds(Some(nx), acc ::: ids, n + 1)
            case None     => Async[F].pure(acc ::: ids)
          }
        }
    pageIds(None, Nil, 0).flatMap(ids => ids.grouped(100).toList.traverse(assocPairs).map(_.flatten.distinct))
  }

  private def assocPairs(ids: List[String]): F[List[(String, String)]] =
    if (ids.isEmpty) Async[F].pure(Nil)
    else
      post(
        "/crm/v4/associations/companies/companies/batch/read",
        Json.obj("inputs" -> Json.fromValues(ids.map(id => Json.obj("id" -> id.asJson))))
      ).map { raw =>
        raw.hcursor.downField("results").values.toList.flatten.flatMap { r =>
          val from = r.hcursor.downField("from").get[String]("id").toOption
          val tos  = r.hcursor.downField("to").values.toList.flatten
          from.toList.flatMap { f =>
            tos.flatMap { t =>
              val labels = t.hcursor
                .downField("associationTypes")
                .values
                .toList
                .flatten
                .flatMap(_.hcursor.get[String]("label").toOption)
              t.hcursor.get[Long]("toObjectId").toOption.map(_.toString).flatMap { to =>
                if (labels.contains("Child Company")) Some((to, f))       // f is the parent, to is the child
                else if (labels.contains("Parent Company")) Some((f, to)) // f is the child, to is the parent
                else None
              }
            }
          }
        }
      }.handleError(_ => Nil)

  // ── http + json helpers ────────────────────────────────────────────────────────────────────────────────
  private def post(path: String, body: Json): F[Json] =
    client.expect[Json](Request[F](Method.POST, uri(path)).withHeaders(auth).withEntity(body))(jsonOf[F, Json])

  private def getJson(path: String): F[Json] =
    client.expect[Json](Request[F](Method.GET, uri(path)).withHeaders(auth))(jsonOf[F, Json])

  private def uri(path: String): Uri = Uri.unsafeFromString(s"$baseUrl$path")

  private def searchBody(props: List[String], since: Option[String]): Json = {
    val filterGroups = since.toList.map(ms =>
      Json.obj(
        "filters" -> Json.arr(
          Json.obj("propertyName" -> "hs_lastmodifieddate".asJson, "operator" -> "GTE".asJson, "value" -> ms.asJson)
        )
      )
    )
    Json.obj(
      "filterGroups" -> filterGroups.asJson,
      "sorts"        -> Json.arr(Json.obj("propertyName" -> "hs_lastmodifieddate".asJson, "direction" -> "ASCENDING".asJson)),
      "properties"   -> props.asJson,
      "limit"        -> 100.asJson
    )
  }

  private def paging(raw: Json): Json = raw.hcursor.downField("paging").focus.getOrElse(Json.Null)

  private def str(props: io.circe.ACursor, key: String): Json =
    props.get[String](key).toOption.filter(_.nonEmpty).fold(Json.Null)(Json.fromString)

  private def str(props: Json, key: String): Json = str(props.hcursor, key)

  private def dateStr(props: io.circe.ACursor, key: String): Json =
    props.get[String](key).toOption.filter(_.nonEmpty).map(_.take(10)).fold(Json.Null)(Json.fromString)

  private def boolOf(props: io.circe.ACursor, key: String): Json =
    Json.fromBoolean(props.get[String](key).toOption.contains("true"))

  // the canonical record: flattened fields + id (sourceId) + a minimal properties carrying the watermark.
  private def canonical(id: String, props: Json)(fields: (String, Json)*): Json =
    Json.fromFields(
      (("id"          -> Json.fromString(id)) +: fields) :+
        ("properties" -> Json.obj("hs_lastmodifieddate" -> str(props.hcursor, "hs_lastmodifieddate")))
    )
}
