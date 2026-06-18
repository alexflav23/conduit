package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access.Action
import com.hypervolt.conduit.access.DataLayer
import com.hypervolt.conduit.access.PolicyEngine
import com.hypervolt.conduit.access.Principal
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// The group entity structure (doc 28 §2.4): one endpoint, two truths. view:entity_structure gates the org
// chart; the inter_entity layer decides WHICH chart: without it, procurement entities and procurement_parent
// edges are ABSENT from the payload — the principal/LRD structure's existence is itself walled (doc 28 §2.3).
// Row filtering happens here (the projection strips fields; hiding whole ENTITIES is this route's job).
final class EntityStructureRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)

  private def hasInterEntity(p: Principal): Boolean =
    p.grants.exists(
      _.permissions.exists(pm =>
        pm.objectType == "entity_structure" && pm.viewableLayers.contains(DataLayer.InterEntity)
      )
    )

  private val structure =
    base.get
      .in("api" / "v1" / "group" / "structure")
      .out(jsonBody[Json])
      .serverLogic(principal =>
        (_: Unit) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "entity_structure"))
            Async[F].pure(
              Left((StatusCode.Forbidden, ApiError("forbidden", "requires view:entity_structure")))
            )
          else {
            val walled = !hasInterEntity(principal)
            sql"""SELECT json_build_object(
                    'id', e.id, 'name', e.name, 'jurisdiction', e.jurisdiction,
                    'functional_currency', e.functional_currency, 'entity_type', e.entity_type,
                    'status', e.status, 'group_parent_id', e.group_parent_id,
                    'procurement_parent_id', e.procurement_parent_id)::text
                  FROM entity e
                  WHERE e.status <> 'retired'
                    AND ($walled = false OR e.entity_type <> 'procurement')
                  ORDER BY e.name"""
              .query[String]
              .to[List]
              .transact(xa)
              .map { rows =>
                val parsed = rows.flatMap(io.circe.parser.parse(_).toOption)
                val shaped =
                  if (walled)
                    // without inter_entity the procurement edge DOES NOT EXIST — removed, never nulled
                    parsed.map(_.hcursor.downField("procurement_parent_id").delete.top.getOrElse(Json.Null))
                  else parsed
                Right(Json.obj("entities" -> Json.fromValues(shaped)))
              }
          }
      )

  val serverEndpoints = List(structure)

  val routes: HttpRoutes[F] = Http4sServerInterpreter[F]().toRoutes(serverEndpoints)
}
