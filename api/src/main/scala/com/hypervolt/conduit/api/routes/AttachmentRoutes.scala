package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access.Action
import com.hypervolt.conduit.access.PolicyEngine
import com.hypervolt.conduit.access.Target
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.close.LineageService
import com.hypervolt.conduit.document.AttachmentInput
import com.hypervolt.conduit.document.AttachmentService
import com.hypervolt.conduit.document.DocumentStorage
import doobie.util.transactor.Transactor
import io.circe.Codec
import io.circe.Json
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._
import java.util.Base64
import java.util.UUID
import org.http4s.HttpRoutes
import scala.util.Try
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// Upload + associate an inbound document (doc 25 §6). Content rides base64 in the JSON body (the house endpoints
// are JSON; the WORM bytes are decoded server-side and sha256-verified).
final case class AttachmentReq(
    kind: String,
    subjectType: String,
    subjectId: String,
    filename: String,
    contentType: String,
    contentBase64: String,
    externalRef: Option[String],
    direction: Option[String],
    source: Option[String],
    dataLayer: Option[String],
    metadata: Option[Json]
)
object AttachmentReq { implicit val codec: Codec[AttachmentReq] = deriveCodec }

// Associated / inbound documents + the revenue-provenance trace (doc 25). The trace is the point: a recognised
// figure drills order → source customer PO → the line's price agreement → the signed contract its tiers came from.
final class AttachmentRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F], storage: DocumentStorage[F]) {

  private val base    = Secured.base[F](auth)
  private val service = new AttachmentService[F](xa, storage)
  private val lineage = new LineageService[F](xa)
  private val anchor  = Target(None, None, None, None)

  private def forbidden(msg: String): (StatusCode, ApiError)  = (StatusCode.Forbidden, ApiError("forbidden", msg))
  private def badRequest(msg: String): (StatusCode, ApiError) = (StatusCode.BadRequest, ApiError("bad_request", msg))

  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => badRequest(s"invalid uuid: $s"))

  private val subjectTypes = Set("order", "party", "price_agreement", "dispatch", "rma", "invoice")
  private val kinds = Set(
    "customer_po",
    "signed_contract",
    "contract_schedule",
    "certificate",
    "delivery_note",
    "proof_of_delivery",
    "correspondence",
    "other"
  )

  private val upload =
    base.post
      .in("api" / "v1" / "documents" / "attachments")
      .in(jsonBody[AttachmentReq])
      .out(statusCode(StatusCode.Created).and(jsonBody[Json]))
      .serverLogic { principal => req =>
        if (!PolicyEngine.authorize(principal, Action.Edit, "document", anchor))
          Async[F].pure(Left(forbidden("requires edit:document")))
        else {
          val parsed = for {
            subject <- uuid(req.subjectId)
            _       <- Either.cond(subjectTypes.contains(req.subjectType), (), badRequest("unknown subject_type"))
            _       <- Either.cond(kinds.contains(req.kind), (), badRequest("unknown kind"))
            bytes <- Try(Base64.getDecoder.decode(req.contentBase64)).toEither.leftMap(_ =>
              badRequest("invalid base64 content")
            )
            _ <- Either.cond(bytes.nonEmpty, (), badRequest("empty content"))
          } yield (subject, bytes)
          parsed match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((subject, bytes)) =>
              service
                .store(
                  AttachmentInput(
                    req.direction.getOrElse("inbound"),
                    req.kind,
                    req.subjectType,
                    subject,
                    req.filename,
                    req.contentType,
                    bytes,
                    req.externalRef,
                    req.source.getOrElse("upload"),
                    Some(principal.userId),
                    req.dataLayer,
                    req.metadata.getOrElse(Json.obj())
                  )
                )
                .map(id => Right(Json.obj("id" -> id.toString.asJson)))
          }
        }
      }

  private val list =
    base.get
      .in("api" / "v1" / "documents" / "attachments")
      .in(query[String]("subjectType").and(query[String]("subjectId")))
      .out(jsonBody[Json])
      .serverLogic { principal =>
        {
          case (subjectType, subjectIdStr) =>
            if (!PolicyEngine.authorize(principal, Action.View, "document", anchor))
              Async[F].pure(Left(forbidden("requires view:document")))
            else
              uuid(subjectIdStr) match {
                case Left(e)        => Async[F].pure(Left(e))
                case Right(subject) => service.listFor(subjectType, subject).map(rows => Right(Json.fromValues(rows)))
              }
        }
      }

  private val download =
    base.get
      .in("api" / "v1" / "documents" / "attachments" / path[String]("id") / "download")
      .out(byteArrayBody)
      .serverLogic { principal => idStr =>
        if (!PolicyEngine.authorize(principal, Action.View, "document", anchor))
          Async[F].pure(Left(forbidden("requires view:document")))
        else
          uuid(idStr) match {
            case Left(e) => Async[F].pure(Left(e))
            case Right(id) =>
              service.download(id).map {
                case None                => Left((StatusCode.NotFound, ApiError("not_found", "no such attachment")))
                case Some((_, _, bytes)) => Right(bytes)
              }
          }
      }

  // doc 25 §4.3 — reconcile the PO's stated total against the RESOLVED order total; drift is flagged, never silent.
  private val reconcile =
    base.post
      .in("api" / "v1" / "documents" / "attachments" / path[String]("id") / "reconcile" / path[String]("orderId"))
      .out(jsonBody[Json])
      .serverLogic { principal =>
        {
          case (idStr, orderStr) =>
            if (!PolicyEngine.authorize(principal, Action.Edit, "document", anchor))
              Async[F].pure(Left(forbidden("requires edit:document")))
            else
              (uuid(idStr), uuid(orderStr)).tupled match {
                case Left(e) => Async[F].pure(Left(e))
                case Right((id, orderId)) =>
                  service.reconcilePo(id, orderId).map {
                    case Left(msg)     => Left((StatusCode.UnprocessableEntity, ApiError("not_reconcilable", msg)))
                    case Right(result) => Right(result)
                  }
              }
        }
      }

  // The revenue-provenance trace (doc 25 §4.4): order → source PO + per-line price agreement → contract documents.
  private val provenance =
    base.get
      .in("api" / "v1" / "orders" / path[String]("id") / "provenance")
      .out(jsonBody[Json])
      .serverLogic { principal => idStr =>
        if (!PolicyEngine.authorize(principal, Action.View, "document", anchor))
          Async[F].pure(Left(forbidden("requires view:document")))
        else
          uuid(idStr) match {
            case Left(e)        => Async[F].pure(Left(e))
            case Right(orderId) => lineage.contractualSources(orderId).map(Right(_))
          }
      }

  val serverEndpoints = List(upload, list, download, reconcile, provenance)

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F])
      .toRoutes(serverEndpoints)
}
