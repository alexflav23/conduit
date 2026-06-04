package com.hypervolt.conduit.dealdesk

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access.Action
import com.hypervolt.conduit.access.Breadth
import com.hypervolt.conduit.access.Principal
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID

final case class Narrative(
    justification: String,
    volumeExpectation: Int,
    volumeDenomination: String,
    strategicImportance: Option[String],
    notes: Option[String],
    docRefs: Option[Json]
)

// Deal Desk (doc 04 §ADLP). Agents assemble the exception narrative + volume expectation; the CEO is the
// SINGLE approver (enforced by the policy layer — only the `ceo` role holds approve:adlp_exception).
// Approval is timed (validity window), volume-contingent and customer-specific; the order releases on approval.
final class DealDeskService[F[_]: Async](xa: Transactor[F]) {

  // Scope-filtered list (doc 05 §2): rows are narrowed to the principal's breadth (all / own), never just
  // anchor-authorised — so an 'own'-breadth agent sees only their own deals' exceptions.
  def listJson(principal: Principal, status: Option[String]): F[List[Json]] = {
    val sel = fr"""SELECT e.id, e.order_id, e.order_line_id, e.party_id, e.list_price, e.max_discount_pct, e.requested_price,
                     e.requested_discount_pct, e.justification, e.volume_expectation, e.volume_denomination,
                     e.strategic_importance, e.notes, e.margin_assessment, e.status, e.approval_memo_ref,
                     e.approved_valid_from, e.approved_valid_to, e.approved_volume_min
                   FROM adlp_exception e JOIN "order" o ON o.id = e.order_id WHERE 1=1"""
    val statusF = status.fold(Fragment.empty)(s => fr"AND e.status = $s")
    val q = sel ++ statusF ++ scopeFilter(principal) ++ fr"ORDER BY e.created_at DESC"
    q.query[ExceptionRow].to[List].map(_.map(_.json)).transact(xa)
  }

  private def scopeFilter(principal: Principal): Fragment = {
    val breadths = principal.grants.flatMap(g =>
      g.permissions.collect { case p if p.objectType == "adlp_exception" && p.action == Action.View => g.breadthOverride.getOrElse(p.dataBreadth) }
    )
    if (breadths.contains(Breadth.All)) Fragment.empty
    else if (breadths.contains(Breadth.Own)) fr"AND o.created_by = ${principal.userId}"
    else fr"AND false"
  }

  // The owning agent of the exception's order — so an 'own'-breadth agent can propose on their own order.
  def ownerOf(exceptionId: UUID): F[Option[UUID]] =
    sql"""SELECT o.created_by FROM adlp_exception e JOIN "order" o ON o.id = e.order_id WHERE e.id = $exceptionId"""
      .query[Option[UUID]].option.map(_.flatten).transact(xa)

  def getJson(id: UUID): F[Option[Json]] =
    (fr"""SELECT id, order_id, order_line_id, party_id, list_price, max_discount_pct, requested_price,
            requested_discount_pct, justification, volume_expectation, volume_denomination,
            strategic_importance, notes, margin_assessment, status, approval_memo_ref,
            approved_valid_from, approved_valid_to, approved_volume_min
          FROM adlp_exception WHERE id = $id""").query[ExceptionRow].option.map(_.map(_.json)).transact(xa)

  // Agent/Deal Desk assembles the narrative + volume expectation. Captures the price band (list + max
  // discount) from the line's resolved rule so banding is explicit. Stays pending_ceo; emits the request event.
  def submit(exceptionId: UUID, n: Narrative, actor: UUID): F[Either[String, Unit]] =
    orderOfException(exceptionId).transact(xa).flatMap {
      case None => "no such exception".asLeft[Unit].pure[F]
      case Some((orderId, status)) =>
        if (status != "pending_ceo" && status != "draft") s"exception not editable in status $status".asLeft[Unit].pure[F]
        else
          (for {
            _ <- captureBanding(exceptionId)
            _ <- sql"""UPDATE adlp_exception SET justification = ${n.justification}, volume_expectation = ${n.volumeExpectation},
                         volume_denomination = ${n.volumeDenomination}, strategic_importance = ${n.strategicImportance},
                         notes = ${n.notes}, doc_refs = ${n.docRefs}, status = 'pending_ceo',
                         party_id = (SELECT sold_to_party_id FROM "order" WHERE id = $orderId)
                       WHERE id = $exceptionId""".update.run
            _ <- sql"INSERT INTO audit_log (entity_type, entity_id, action, actor_user_id) VALUES ('adlp_exception', $exceptionId, 'submit_narrative', $actor)".update.run
            _ <- OutboxRepo.append(event(exceptionId, orderId, "adlp.exception.requested",
                   Json.obj("volume_expectation" -> n.volumeExpectation.asJson, "volume_denomination" -> n.volumeDenomination.asJson)))
          } yield ().asRight[String]).transact(xa)
    }

  // CEO-only decision (the route gates approve:adlp_exception). Maker ≠ checker. Approve records the immutable
  // memo + timed window + volume contingency; reject holds the order. Order releases when all exceptions clear.
  def decide(exceptionId: UUID, approver: UUID, approve: Boolean, memo: Option[String], validFrom: Option[Instant], validTo: Option[Instant], volumeMin: Option[Int]): F[Either[String, Unit]] =
    decisionContext(exceptionId).transact(xa).flatMap {
      case None => "no such exception".asLeft[Unit].pure[F]
      case Some((orderId, status, createdBy)) =>
        if (status != "pending_ceo") s"exception not pending (status $status)".asLeft[Unit].pure[F]
        else if (createdBy.contains(approver)) "maker cannot be checker (the proposing agent cannot approve)".asLeft[Unit].pure[F]
        else if (approve && memo.isEmpty) "an approval memo is required (immutable record)".asLeft[Unit].pure[F]
        else if (approve)
          (for {
            _ <- sql"""UPDATE adlp_exception SET status='approved', approved_by=$approver, approval_memo_ref=$memo,
                         approved_valid_from=$validFrom, approved_valid_to=$validTo, approved_volume_min=$volumeMin,
                         decided_at=now() WHERE id=$exceptionId""".update.run
            _ <- sql"UPDATE order_line SET adlp_category='exception', status='open' WHERE id=(SELECT order_line_id FROM adlp_exception WHERE id=$exceptionId)".update.run
            _ <- OutboxRepo.append(event(exceptionId, orderId, "adlp.exception.approved",
                   Json.obj("approved_by" -> approver.toString.asJson, "valid_from" -> validFrom.map(_.toString).asJson, "valid_to" -> validTo.map(_.toString).asJson, "volume_min" -> volumeMin.asJson)))
            released <- releaseOrderIfReady(orderId)
            _ <- if (released) OutboxRepo.append(event(exceptionId, orderId, "order.placed", Json.obj("released_from" -> "pending_ceo".asJson))) else ().pure[ConnectionIO]
          } yield ().asRight[String]).transact(xa)
        else
          (sql"UPDATE adlp_exception SET status='rejected', approved_by=$approver, approval_memo_ref=$memo, decided_at=now() WHERE id=$exceptionId".update.run *>
            OutboxRepo.append(event(exceptionId, orderId, "adlp.exception.rejected", Json.obj("rejected_by" -> approver.toString.asJson)))).transact(xa).as(().asRight[String])
    }

  // ----- internals -----

  private def captureBanding(exceptionId: UUID): ConnectionIO[Int] =
    sql"""UPDATE adlp_exception e
          SET list_price = pr.authorised_price, max_discount_pct = pr.max_discount_pct
          FROM order_line ol JOIN price_rule pr ON pr.id = ol.price_rule_id
          WHERE e.id = $exceptionId AND ol.id = e.order_line_id""".update.run

  private def releaseOrderIfReady(orderId: UUID): ConnectionIO[Boolean] =
    sql"SELECT count(*) FROM adlp_exception WHERE order_id = $orderId AND status NOT IN ('approved','rejected')".query[Long].unique.flatMap { pending =>
      if (pending == 0L)
        sql"""UPDATE "order" SET status='placed', updated_at=now() WHERE id=$orderId AND status='pending_ceo'""".update.run.map(_ > 0)
      else false.pure[ConnectionIO]
    }

  private def orderOfException(id: UUID): ConnectionIO[Option[(UUID, String)]] =
    sql"SELECT order_id, status FROM adlp_exception WHERE id = $id".query[(UUID, String)].option

  private def decisionContext(id: UUID): ConnectionIO[Option[(UUID, String, Option[UUID])]] =
    sql"""SELECT e.order_id, e.status, o.created_by FROM adlp_exception e JOIN "order" o ON o.id = e.order_id WHERE e.id = $id"""
      .query[(UUID, String, Option[UUID])].option

  private def event(exceptionId: UUID, orderId: UUID, eventType: String, payload: Json): OutboxEvent =
    OutboxEvent(UUID.randomUUID(), eventType, 1, "order", orderId, orderId.toString, None, None, None,
      payload.deepMerge(Json.obj("exception_id" -> exceptionId.toString.asJson)), Instant.now())
}

private final case class ExceptionRow(
    id: UUID,
    orderId: UUID,
    orderLineId: Option[UUID],
    partyId: Option[UUID],
    listPrice: Option[BigDecimal],
    maxDiscountPct: Option[BigDecimal],
    requestedPrice: Option[BigDecimal],
    requestedDiscountPct: Option[BigDecimal],
    justification: Option[String],
    volumeExpectation: Option[Int],
    volumeDenomination: Option[String],
    strategicImportance: Option[String],
    notes: Option[String],
    marginAssessment: Option[Json],
    status: String,
    approvalMemoRef: Option[String],
    approvedValidFrom: Option[Instant],
    approvedValidTo: Option[Instant],
    approvedVolumeMin: Option[Int]
) {
  def json: Json = Json.obj(
    "id"                     -> id.toString.asJson,
    "order_id"               -> orderId.toString.asJson,
    "order_line_id"          -> orderLineId.map(_.toString).asJson,
    "party_id"               -> partyId.map(_.toString).asJson,
    "list_price"             -> listPrice.map(_.toString).asJson,
    "max_discount_pct"       -> maxDiscountPct.map(_.toString).asJson,
    "requested_price"        -> requestedPrice.map(_.toString).asJson,
    "requested_discount_pct" -> requestedDiscountPct.map(_.toString).asJson,
    "justification"          -> justification.asJson,
    "volume_expectation"     -> volumeExpectation.asJson,
    "volume_denomination"    -> volumeDenomination.asJson,
    "strategic_importance"   -> strategicImportance.asJson,
    "notes"                  -> notes.asJson,
    "margin_assessment"      -> marginAssessment.getOrElse(Json.Null),
    "status"                 -> status.asJson,
    "approval_memo_ref"      -> approvalMemoRef.asJson,
    "approved_valid_from"    -> approvedValidFrom.map(_.toString).asJson,
    "approved_valid_to"      -> approvedValidTo.map(_.toString).asJson,
    "approved_volume_min"    -> approvedVolumeMin.asJson
  )
}
