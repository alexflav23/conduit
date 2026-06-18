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

// The live HubSpot CRM v3 implementation of the HubSpotApi seam (S2.1, spec 37 §1). Uses the search endpoint so a
// warm pull fetches only rows with hs_lastmodifieddate >= the cursor, ascending — the watermark cursor the
// IngestRunner advances. CRUCIALLY it NORMALIZES each v3 object {id, properties:{…}} into the canonical record the
// shared SnapshotLoader handler consumes (the same shape as the boot ndjson), while keeping `id` (the connector's
// sourceId) and `properties.hs_lastmodifieddate` (the connector's watermark) — so the connector + its test, and
// the live+boot mapping path, are all unchanged. Per spec 37 the field map is owned here, at the source seam.
final class HttpHubSpotApi[F[_]: Async](client: Client[F], token: String, baseUrl: String) extends HubSpotApi[F] {

  // dataset -> (HubSpot properties to request, normalize one v3 result row -> the canonical record)
  private val specs: Map[String, (List[String], (String, Json) => Json)] = Map(
    "companies" -> (
      List("name", "domain", "industry", "country", "hs_lastmodifieddate"),
      (id, p) =>
        canonical(id, p, "hs_lastmodifieddate")(
          "company_id" -> id.asJson,
          "name"       -> str(p, "name"),
          "domain"     -> str(p, "domain"),
          "industry"   -> str(p, "industry"),
          "country"    -> str(p, "country")
        )
    ),
    "contacts" -> (
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
        canonical(id, p, "hs_lastmodifieddate")(
          "contact_id" -> id.asJson,
          "email"      -> str(p, "email"),
          "first_name" -> str(p, "firstname"),
          "last_name"  -> str(p, "lastname"),
          "phone"      -> str(p, "phone"),
          "company"    -> str(p, "company"),
          "company_id" -> str(p, "associatedcompanyid"),
          "job_title"  -> str(p, "jobtitle"),
          "lifecycle"  -> str(p, "lifecyclestage"),
          "created"    -> str(p, "createdate").asString.map(_.take(10)).map(Json.fromString).getOrElse(Json.Null)
        )
    )
  )

  def get(objectType: String, modifiedSince: Option[String]): F[Json] =
    specs.get(objectType) match {
      case None =>
        Async[F].raiseError(
          new IllegalArgumentException(s"hubspot live pull not wired for '$objectType' (S2.1: companies, contacts)")
        )
      case Some((props, normalize)) =>
        val filterGroups =
          modifiedSince.toList.map(ms =>
            Json.obj(
              "filters" -> Json.arr(
                Json.obj(
                  "propertyName" -> "hs_lastmodifieddate".asJson,
                  "operator"     -> "GTE".asJson,
                  "value"        -> ms.asJson
                )
              )
            )
          )
        val body = Json.obj(
          "filterGroups" -> filterGroups.asJson,
          "sorts" -> Json.arr(
            Json.obj("propertyName" -> "hs_lastmodifieddate".asJson, "direction" -> "ASCENDING".asJson)
          ),
          "properties" -> props.asJson,
          "limit"      -> 100.asJson
        )
        val req = Request[F](
          method = Method.POST,
          uri = Uri.unsafeFromString(s"$baseUrl/crm/v3/objects/$objectType/search")
        ).withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token))).withEntity(body)
        client.expect[Json](req)(jsonOf[F, Json]).map(raw => repackage(raw, normalize))
    }

  // Re-emit the page in the shape the connector + handler expect: {results:[<normalized>], paging:{…}} — the
  // normalized rows keep `id` and `properties.hs_lastmodifieddate` so keying + watermark advance are unchanged.
  private def repackage(raw: Json, normalize: (String, Json) => Json): Json = {
    val rows = raw.hcursor.downField("results").values.toList.flatten
    val out = rows.flatMap { row =>
      val c = row.hcursor
      (c.get[String]("id").toOption, c.downField("properties").focus).mapN((id, props) => normalize(id, props))
    }
    Json.obj("results" -> Json.fromValues(out), "paging" -> raw.hcursor.downField("paging").focus.getOrElse(Json.Null))
  }

  private def str(props: Json, key: String): Json =
    props.hcursor.get[String](key).toOption.filter(_.nonEmpty).fold(Json.Null)(Json.fromString)

  // Build the canonical record: the flattened fields + `id` (sourceId) + a minimal `properties` carrying the
  // watermark the connector reads.
  private def canonical(id: String, props: Json, watermark: String)(fields: (String, Json)*): Json =
    Json.fromFields(
      (("id"          -> Json.fromString(id)) +: fields) :+
        ("properties" -> Json.obj(watermark -> str(props, watermark)))
    )
}
