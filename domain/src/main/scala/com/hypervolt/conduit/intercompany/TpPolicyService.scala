package com.hypervolt.conduit.intercompany

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID

final case class NewPolicy(
    fromEntity: UUID,
    toEntity: UUID,
    method: String,
    markupPct: Option[BigDecimal],
    resaleMarginPct: Option[BigDecimal],
    fixedPrice: Option[BigDecimal],
    tpCurrency: Option[String],
    documentationMethod: Option[String]
)

// Transfer-price policy governance (doc 13 §2.1, doc 05 §4): a policy is proposed (draft) by finance and
// activated by the CFO — proposer ≠ approver. Activation upserts the inter_entity price_rule view and emits
// transfer_price_policy.changed. Versioned + audited.
final class TpPolicyService[F[_]: Async](xa: Transactor[F]) {

  def create(p: NewPolicy, actor: UUID): F[Either[String, UUID]] =
    TransferPricing.Method.fromCode(p.method) match {
      case None => s"unknown tp method ${p.method}".asLeft[UUID].pure[F]
      case Some(_) =>
        sql"""INSERT INTO transfer_price_policy
                (from_entity_id, to_entity_id, method, basis, markup_pct, resale_margin_pct, fixed_price,
                 tp_currency, documentation_method, status, owner_user_id)
              VALUES (${p.fromEntity}, ${p.toEntity}, ${p.method}, 'landed_cost', ${p.markupPct},
                 ${p.resaleMarginPct}, ${p.fixedPrice}, ${p.tpCurrency}, ${p.documentationMethod}, 'draft', $actor)
              RETURNING id""".query[UUID].unique.transact(xa).map(_.asRight[String])
    }

  // Maker-checker: the approver must differ from the proposer. On approve the policy goes active, the
  // inter_entity price_rule view is upserted, and the change is audited via the outbox.
  def approve(policyId: UUID, actor: UUID): F[Either[String, Unit]] =
    owner(policyId).transact(xa).flatMap {
      case None                         => "unknown policy".asLeft[Unit].pure[F]
      case Some(o) if o.contains(actor) => "maker cannot approve their own policy".asLeft[Unit].pure[F]
      case Some(_) =>
        (activate(policyId, actor) *> upsertPriceRule(policyId) *> auditChange(policyId, actor))
          .transact(xa)
          .as(().asRight[String])
    }

  private def owner(id: UUID): ConnectionIO[Option[Option[UUID]]] =
    sql"SELECT owner_user_id FROM transfer_price_policy WHERE id = $id".query[Option[UUID]].option

  private def activate(id: UUID, actor: UUID): ConnectionIO[Int] =
    sql"""UPDATE transfer_price_policy SET status='active', approved_by=$actor, updated_at=now() WHERE id=$id""".update.run

  // The inter_entity price_rule is the layer-walled, runtime resolution view of the active policy (doc 13 §2.1).
  private def upsertPriceRule(id: UUID): ConnectionIO[Int] =
    sql"""INSERT INTO price_rule (surface, currency, authorised_price, from_entity_id, to_entity_id, tp_method, tp_markup_pct, status)
          SELECT 'inter_entity', COALESCE(p.tp_currency, e.functional_currency), 0, p.from_entity_id, p.to_entity_id,
                 p.method, p.markup_pct, 'active'
          FROM transfer_price_policy p JOIN entity e ON e.id = p.from_entity_id WHERE p.id = $id""".update.run

  private def auditChange(id: UUID, actor: UUID): ConnectionIO[Int] =
    OutboxRepo.append(
      OutboxEvent(
        UUID.randomUUID(),
        "transfer_price_policy.changed",
        1,
        "transfer_price_policy",
        id,
        id.toString,
        None,
        None,
        None,
        Json
          .obj("policy_id" -> id.toString.asJson, "approved_by" -> actor.toString.asJson, "status" -> "active".asJson),
        Instant.now()
      )
    )
}
