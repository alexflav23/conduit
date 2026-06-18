package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.crm.CrmReadRepo
import com.hypervolt.conduit.crm.MasterAccountRepo
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.util.UUID
import org.http4s.HttpRoutes
import scala.util.Try
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// CRM reads (spec/ui/22-crm.md): the scope-filtered party worklist and the deal pipeline. Customer master is part
// of the commerce domain, so the gate is the same view the order desk needs (view:order), widened to anyone who
// can see the forecast board (view:pipeline_coverage) — sales, deal desk and CEO. Identity-only fields, so there
// is no layer wall to project here; credit + PII stay behind the per-party credit-terms route.
final class CrmRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def gate(p: Principal): Boolean =
    PolicyEngine.hasPermission(p, Action.View, "order") || PolicyEngine.hasPermission(
      p,
      Action.View,
      "pipeline_coverage"
    )
  private val denied = err(StatusCode.Forbidden, "forbidden", "requires view:order")

  private def optUuid(s: Option[String]): Either[(StatusCode, ApiError), Option[UUID]] =
    s.filter(_.nonEmpty)
      .traverse(v =>
        Try(UUID.fromString(v)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $v"))
      )

  private val parties =
    base.get
      .in("api" / "v1" / "crm" / "parties")
      .in(query[Option[String]]("market"))
      .in(query[Option[String]]("sector"))
      .in(query[Option[String]]("q"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (market, sector, q, limit) =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else
            optUuid(market) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(mk) =>
                val cap = limit.getOrElse(200).min(500).max(1)
                (
                  CrmReadRepo.listParties(mk, sector.filter(_.nonEmpty), q.filter(_.nonEmpty), cap),
                  CrmReadRepo.sectors
                ).tupled
                  .transact(xa)
                  .map {
                    case (rows, sectors) =>
                      Right(Json.obj("rows" -> Json.fromValues(rows), "sectors" -> sectors.asJson))
                  }
            }
      })

  private val pipeline =
    base.get
      .in("api" / "v1" / "crm" / "pipeline")
      .in(query[Option[String]]("market"))
      .in(query[Option[Int]]("limit"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (_, limit) =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else CrmReadRepo.pipeline(limit.getOrElse(200).min(500).max(1)).transact(xa).map(Right(_))
      })

  // The attributed deal/PO book — every historical customer deal tied to the installer/wholesaler/retail company
  // that placed it, paginated + filterable by segment / won / company search.
  private val deals =
    base.get
      .in("api" / "v1" / "crm" / "deals")
      .in(query[Option[String]]("segment"))
      .in(query[Option[String]]("pipeline"))
      .in(query[Option[String]]("status"))
      .in(query[Option[String]]("q"))
      .in(query[Option[String]]("sort"))
      .in(query[Option[String]]("dir"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Int]]("offset"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (segment, pipeline, status, q, sort, dir, limitF, offsetF) =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else {
            val lim = limitF.getOrElse(50).min(200).max(1)
            val off = offsetF.getOrElse(0).max(0)
            val ne  = (s: Option[String]) => s.filter(_.nonEmpty)
            (
              CrmReadRepo.deals(ne(segment), ne(pipeline), ne(status), ne(q), ne(sort), ne(dir), lim, off),
              CrmReadRepo.dealsCount(ne(segment), ne(pipeline), ne(status), ne(q))
            ).tupled
              .transact(xa)
              .map {
                case (rows, total) =>
                  Right(
                    Json.obj(
                      "rows"   -> Json.fromValues(rows),
                      "total"  -> total.asJson,
                      "limit"  -> lim.asJson,
                      "offset" -> off.asJson
                    )
                  )
              }
          }
      })

  private val dealsSummary =
    base.get
      .in("api" / "v1" / "crm" / "deals" / "summary")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else CrmReadRepo.dealsSummary.transact(xa).map(Right(_))
      )

  // Master accounts: the Conduit entity (golden record), with MRPeasy + HubSpot + contacts + branches as parts.
  private val accounts =
    base.get
      .in("api" / "v1" / "crm" / "accounts")
      .in(query[Option[String]]("segment"))
      .in(query[Option[String]]("q"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Int]]("offset"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (segment, q, limitF, offsetF) =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else {
            val lim = limitF.getOrElse(50).min(200).max(1)
            val off = offsetF.getOrElse(0).max(0)
            val ne  = (s: Option[String]) => s.filter(_.nonEmpty)
            (
              CrmReadRepo.listAccounts(ne(segment), ne(q), lim, off),
              CrmReadRepo.countAccounts(ne(segment), ne(q))
            ).tupled
              .transact(xa)
              .map {
                case (rows, total) =>
                  Right(
                    Json.obj(
                      "rows"   -> Json.fromValues(rows),
                      "total"  -> total.asJson,
                      "limit"  -> lim.asJson,
                      "offset" -> off.asJson
                    )
                  )
              }
          }
      })

  private val accountDetail =
    base.get
      .in("api" / "v1" / "crm" / "accounts" / path[String]("id"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        idS =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else
            optUuid(Some(idS)) match {
              case Left(e)     => Async[F].pure(Left(e))
              case Right(None) => Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", "invalid id")))
              case Right(Some(id)) =>
                CrmReadRepo.accountDetail(id).transact(xa).map {
                  case Some(j) => Right(j)
                  case None    => Left(err(StatusCode.NotFound, "not_found", s"no account $idS"))
                }
            }
      )

  // The installer/wholesaler's end-customers (phone-bridged charger owners + end-customer contacts). Paginated.
  private val accountCustomers =
    base.get
      .in("api" / "v1" / "crm" / "accounts" / path[String]("id") / "customers")
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Int]]("offset"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (idS, limitF, offsetF) =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else
            optUuid(Some(idS)) match {
              case Right(Some(id)) =>
                val lim = limitF.getOrElse(50).min(200).max(1)
                val off = offsetF.getOrElse(0).max(0)
                (CrmReadRepo.accountCustomers(id, lim, off), CrmReadRepo.countAccountCustomers(id)).tupled
                  .transact(xa)
                  .map {
                    case (rows, total) =>
                      Right(
                        Json.obj(
                          "rows"   -> Json.fromValues(rows),
                          "total"  -> total.asJson,
                          "limit"  -> lim.asJson,
                          "offset" -> off.asJson
                        )
                      )
                  }
              case _ => Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", "invalid id")))
            }
      })

  // ---- Master-account review: the fuzzy candidates the model didn't auto-merge, with its verdict + reasoning ----
  private def canEdit(p: Principal): Boolean = PolicyEngine.hasPermission(p, Action.Edit, "credit_profile")
  private val editDenied                     = err(StatusCode.Forbidden, "forbidden", "requires edit:credit_profile")

  private val reviewQueue =
    base.get
      .in("api" / "v1" / "crm" / "account-candidates")
      .in(query[Option[String]]("q"))
      .in(query[Option[Int]]("limit"))
      .in(query[Option[Int]]("offset"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (q, limitF, offsetF) =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else {
            val lim = limitF.getOrElse(50).min(200).max(1)
            val off = offsetF.getOrElse(0).max(0)
            (
              MasterAccountRepo.reviewQueue(q.filter(_.nonEmpty), lim, off),
              MasterAccountRepo.reviewCount(q.filter(_.nonEmpty))
            ).tupled
              .transact(xa)
              .map {
                case (rows, total) =>
                  Right(
                    Json.obj(
                      "rows"   -> Json.fromValues(rows),
                      "total"  -> total.asJson,
                      "limit"  -> lim.asJson,
                      "offset" -> off.asJson
                    )
                  )
              }
          }
      })

  private def field(j: Json, k: String): Option[String] = j.hcursor.get[String](k).toOption.filter(_.nonEmpty)

  private val mergeAccounts =
    base.post
      .in("api" / "v1" / "crm" / "account-candidates" / "merge")
      .in(jsonBody[Json])
      .out(jsonBody[Json])
      .serverLogic(p =>
        body =>
          if (!canEdit(p)) Async[F].pure(Left(editDenied))
          else
            (
              field(body, "hs_company_id"),
              field(body, "winner_party_id").flatMap(s => Try(UUID.fromString(s)).toOption)
            ) match {
              case (Some(hs), Some(winner)) =>
                MasterAccountRepo
                  .merge(winner, hs, p.userId.toString, "manual review accept")
                  .transact(xa)
                  .map(n => Right(Json.obj("merged" -> (n > 0).asJson)))
              case _ =>
                Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", "need hs_company_id + winner_party_id")))
            }
      )

  private val rejectCandidate =
    base.post
      .in("api" / "v1" / "crm" / "account-candidates" / "reject")
      .in(jsonBody[Json])
      .out(jsonBody[Json])
      .serverLogic(p =>
        body =>
          if (!canEdit(p)) Async[F].pure(Left(editDenied))
          else
            field(body, "hs_company_id") match {
              case Some(hs) =>
                MasterAccountRepo
                  .reject(hs, p.userId.toString)
                  .transact(xa)
                  .map(n => Right(Json.obj("rejected" -> n.asJson)))
              case None => Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", "need hs_company_id")))
            }
      )

  private val setParent =
    base.post
      .in("api" / "v1" / "crm" / "accounts" / path[String]("id") / "parent")
      .in(jsonBody[Json])
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (idS, body) =>
          if (!canEdit(p)) Async[F].pure(Left(editDenied))
          else
            Try(UUID.fromString(idS)).toOption match {
              case None => Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", "invalid id")))
              case Some(child) =>
                val parent = field(body, "parent_id").flatMap(s => Try(UUID.fromString(s)).toOption)
                MasterAccountRepo.setParent(child, parent).transact(xa).map(_ => Right(Json.obj("ok" -> true.asJson)))
            }
      })

  val serverEndpoints = List(
    accounts,
    accountDetail,
    accountCustomers,
    reviewQueue,
    mergeAccounts,
    rejectCandidate,
    setParent,
    parties,
    pipeline,
    dealsSummary,
    deals
  )

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F])
      .toRoutes(serverEndpoints)
}
