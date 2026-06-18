package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.commission.CommissionRepo
import doobie.implicits._
import doobie.postgres.implicits._
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

// Commission read surface (M5). Accrual/posting are NOT here — they post to TigerBeetle and so live in the
// CommissionConsumer (house rule: no TB on the request path). This exposes the agent statement (posted total,
// reconciles to the ledger COMM_PAYABLE balance) and the entry ledger for the desk.
final class CommissionRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))
  private def forbid(p: Principal): Option[(StatusCode, ApiError)] =
    Option.unless(PolicyEngine.hasPermission(p, Action.View, "commission_entry"))(
      err(StatusCode.Forbidden, "forbidden", "requires view:commission_entry")
    )

  // ----- agent statement (posted total) -----
  private val statement =
    base.get
      .in("api" / "v1" / "commission" / "statement" / path[String]("agentId"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        agentS =>
          forbid(p) match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              uuid(agentS) match {
                case Left(e) => Async[F].pure(Left(e))
                case Right(agentId) =>
                  CommissionRepo
                    .postedTotal(agentId)
                    .transact(xa)
                    .map(total => Right(Json.obj("agent_id" -> agentS.asJson, "posted_total" -> total.asJson)))
              }
          }
      )

  // ----- entry ledger (optionally by agent / status) -----
  private val entries =
    base.get
      .in("api" / "v1" / "commission" / "entries")
      .in(query[Option[String]]("agent"))
      .in(query[Option[String]]("status"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (agentF, statusF) =>
          forbid(p) match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              val agent    = agentF.flatMap(s => Try(UUID.fromString(s)).toOption)
              val byAgent  = agent.fold(doobie.Fragment.empty)(a => fr"AND e.agent_id = $a")
              val byStatus = statusF.fold(doobie.Fragment.empty)(s => fr"AND e.status = $s")
              (fr"""SELECT e.id, e.agent_id, e.order_id, e.basis_amount, e.rate_applied, e.amount, e.currency,
                      e.status, e.kind, e.created_at::text
                    FROM commission_entry e WHERE 1=1""" ++ byAgent ++ byStatus ++ fr"ORDER BY e.created_at DESC LIMIT 500")
                .query[(UUID, UUID, Option[UUID], BigDecimal, BigDecimal, BigDecimal, String, String, String, String)]
                .to[List]
                .transact(xa)
                .map(rows =>
                  Right(Json.fromValues(rows.map {
                    case (id, ag, ord, basis, rate, amt, ccy, st, kind, at) =>
                      Json.obj(
                        "id"         -> id.toString.asJson,
                        "agent_id"   -> ag.toString.asJson,
                        "order_id"   -> ord.map(_.toString).asJson,
                        "basis"      -> basis.asJson,
                        "rate"       -> rate.asJson,
                        "amount"     -> amt.asJson,
                        "currency"   -> ccy.asJson,
                        "status"     -> st.asJson,
                        "kind"       -> kind.asJson,
                        "created_at" -> at.asJson
                      )
                  }))
                )
          }
      })

  // ----- the commission book (the desk's /finance/commission): rows + accrued/posted/clawed totals -----
  private val book =
    base.get
      .in("api" / "v1" / "finance" / "commission")
      .in(query[Option[String]]("period"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          forbid(p) match {
            case Some(e) => Async[F].pure(Left(e))
            case None =>
              (
                sql"""SELECT e.id, e.order_id, e.basis_amount, e.rate_applied, e.amount, e.status, e.kind, e.agent_id
                     FROM commission_entry e ORDER BY e.created_at DESC LIMIT 500"""
                  .query[(UUID, Option[UUID], BigDecimal, BigDecimal, BigDecimal, String, String, UUID)]
                  .to[List],
                sql"""SELECT
                       COALESCE(sum(amount) FILTER (WHERE kind <> 'clawback'), 0),
                       COALESCE(sum(amount) FILTER (WHERE status = 'posted'), 0),
                       COALESCE(sum(amount) FILTER (WHERE kind = 'clawback'), 0)
                     FROM commission_entry""".query[(BigDecimal, BigDecimal, BigDecimal)].unique
              ).tupled.transact(xa).map {
                case (rows, (accrued, posted, clawed)) =>
                  Right(
                    Json.obj(
                      "scope" -> "all".asJson,
                      "totals" -> Json
                        .obj("accrued" -> accrued.asJson, "posted" -> posted.asJson, "clawed" -> clawed.asJson),
                      "rows" -> Json.fromValues(rows.map {
                        case (id, ord, basis, rate, amt, st, kind, ag) =>
                          Json.obj(
                            "id"     -> id.toString.asJson,
                            "order"  -> ord.map(_.toString).asJson,
                            "basis"  -> basis.asJson,
                            "rate"   -> rate.asJson,
                            "amount" -> amt.asJson,
                            "status" -> st.asJson,
                            "scheme" -> kind.asJson,
                            "agent"  -> ag.toString.asJson
                          )
                      })
                    )
                  )
              }
          }
      )

  val serverEndpoints = List(statement, entries, book)

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(serverEndpoints)
}
