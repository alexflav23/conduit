package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.document.DocumentQueryRepo
import com.hypervolt.conduit.document.DocumentStorage
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import java.util.UUID
import org.http4s.HttpRoutes
import scala.util.Try
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import com.hypervolt.conduit.api.ApiMetrics
import sttp.tapir.server.http4s.Http4sServerInterpreter

// The documents surface (doc 17 §6/§9): list the legal artefacts for an order/invoice, fetch one's metadata, and
// download the PDF bytes from the WORM store. View-gated on `document`; the money fields are commercial-layer and
// stripped by Projection for principals who can't see them. The PDF itself is fetched from object storage (S3) —
// no TigerBeetle here, so it stays in the API.
final class DocumentRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F], storage: DocumentStorage[F]) {

  private val base = Secured.base[F](auth)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))
  private def gate(p: Principal): Boolean = PolicyEngine.hasPermission(p, Action.View, "document")
  private val forbid                      = err(StatusCode.Forbidden, "forbidden", "requires view:document")
  private def project(p: Principal, rows: List[Json]): Json =
    Json.fromValues(rows.map(r => Projection.projectFor(p, "document", r)))

  private val list =
    base.get
      .in("api" / "v1" / "documents")
      .in(query[Option[String]]("order_id"))
      .in(query[Option[String]]("invoice_no"))
      .out(jsonBody[Json])
      .serverLogic(p => {
        case (orderId, invoiceNo) =>
          if (!gate(p)) Async[F].pure(Left(forbid))
          else
            (orderId, invoiceNo) match {
              case (Some(oid), _) =>
                uuid(oid) match {
                  case Left(e)  => Async[F].pure(Left(e))
                  case Right(o) => DocumentQueryRepo.listForOrder(o).transact(xa).map(r => Right(project(p, r)))
                }
              case (None, Some(no)) =>
                DocumentQueryRepo.listForInvoiceNo(no).transact(xa).map(r => Right(project(p, r)))
              case (None, None) =>
                Async[F].pure(Left(err(StatusCode.BadRequest, "bad_request", "order_id or invoice_no required")))
            }
      })

  private val byId =
    base.get
      .in("api" / "v1" / "documents" / path[String]("id"))
      .out(jsonBody[Json])
      .serverLogic(p =>
        id =>
          if (!gate(p)) Async[F].pure(Left(forbid))
          else
            uuid(id) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(d) =>
                DocumentQueryRepo.byId(d).transact(xa).map {
                  case None      => Left(err(StatusCode.NotFound, "not_found", s"unknown document $id"))
                  case Some(row) => Right(Projection.projectFor(p, "document", row))
                }
            }
      )

  private val pdf =
    base.get
      .in("api" / "v1" / "documents" / path[String]("id") / "pdf")
      .out(byteArrayBody)
      .out(header[String]("Content-Type"))
      .serverLogic(p =>
        id =>
          if (!gate(p)) Async[F].pure(Left(forbid))
          else
            uuid(id) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(d) =>
                DocumentQueryRepo.storageRef(d).transact(xa).flatMap {
                  case None => Async[F].pure(Left(err(StatusCode.NotFound, "not_found", s"no finalised document $id")))
                  case Some((uri, _)) =>
                    storage
                      .get(uri)
                      .attempt
                      .map(
                        _.bimap(
                          _ => err(StatusCode.NotFound, "not_found", "document bytes not in storage"),
                          bytes => (bytes, "application/pdf")
                        )
                      )
                }
            }
      )

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(List(list, byId, pdf))
}
