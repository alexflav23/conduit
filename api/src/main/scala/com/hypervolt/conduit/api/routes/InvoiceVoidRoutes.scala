package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// The invoice-invalidation entry point (doc 13 §void). The API CANNOT post the reversal — it has no TigerBeetle
// client (the no-TB-in-API rule) — so it records the intent as an `invoice.void_requested` event in the outbox
// (one tx) and the consumer performs the immutable reversal. edit:order to void; a refund additionally needs
// approve:order (maker-checker — money leaving the business is a higher bar than a billing correction).
final class InvoiceVoidRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base  = Secured.base[F](auth)
  private val kinds = Set("mistake", "cancellation", "refund", "correction")

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))

  private val voidInvoice =
    base.post
      .in("api" / "v1" / "invoices" / path[String]("invoice_no") / "void")
      .in(jsonBody[Json])
      .out(statusCode(StatusCode.Accepted).and(jsonBody[Json]))
      .serverLogic(p => {
        case (invoiceNo, body) =>
          val kind   = body.hcursor.get[String]("kind").getOrElse("")
          val reason = body.hcursor.get[String]("reason").getOrElse("")
          if (!PolicyEngine.hasPermission(p, Action.Edit, "order"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires edit:order")))
          else if (!kinds.contains(kind))
            Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", s"invalid kind '$kind'")))
          else if (reason.trim.isEmpty)
            Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", "a void reason is required")))
          else if (kind == "refund" && !PolicyEngine.hasPermission(p, Action.Approve, "order"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "a refund requires approve:order")))
          else
            resolve(invoiceNo).transact(xa).flatMap {
              case None => Async[F].pure(Left(err(StatusCode.NotFound, "not_found", s"unknown invoice $invoiceNo")))
              case Some((invId, orderId)) =>
                OutboxRepo
                  .append(requested(invId, orderId, invoiceNo, kind, reason, p.userId.toString))
                  .transact(xa)
                  .as(
                    Right(
                      Json.obj(
                        "invoice_no" -> invoiceNo.asJson,
                        "status"     -> "void_requested".asJson,
                        "kind"       -> kind.asJson
                      )
                    )
                  )
            }
      })

  private def resolve(invoiceNo: String) =
    sql"SELECT id, order_id FROM order_invoice WHERE invoice_no = $invoiceNo".query[(UUID, UUID)].option

  private def requested(invId: UUID, orderId: UUID, invoiceNo: String, kind: String, reason: String, actor: String) =
    OutboxEvent(
      UUID.randomUUID(),
      "invoice.void_requested",
      1,
      "order",
      orderId,
      orderId.toString,
      None,
      Some(com.hypervolt.conduit.revenue.CollectionCycle.correlationId(invId)), // the cycle thread
      None,                                                                     // root of the cycle
      Json.obj(
        "order_invoice_id" -> invId.toString.asJson,
        "invoice_no"       -> invoiceNo.asJson,
        "kind"             -> kind.asJson,
        "reason"           -> reason.asJson,
        "requested_by"     -> actor.asJson
      ),
      Instant.now()
    )

  val routes: HttpRoutes[F] = Http4sServerInterpreter[F]().toRoutes(voidInvoice)
}
