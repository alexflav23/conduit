package com.hypervolt.conduit.pricing

import cats.effect.Async
import cats.syntax.all._
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

// One band of a requested tier ladder (doc 24 §2/§6). from_qty is the band floor, up_to_qty the ceiling
// (None = open-ended); price is the ex-VAT authorized tier price.
final case class TierBand(
    variantId: UUID,
    fromQty: Int,
    upToQty: Option[Int],
    price: BigDecimal,
    taxRegime: String
)

// A salesperson's price-tier request (doc 24 §6) — the renamed "ADLP exception". It becomes a DRAFT
// price_agreement (customer_set scope) + its tier rules + the named customers; governed by maker-checker activation.
final case class TierRequest(
    name: String,
    currency: String,
    customers: List[UUID],
    bands: List[TierBand],
    validFrom: Instant,
    validTo: Option[Instant],
    baseVolumeBasis: String,
    terms: Json,
    justification: Option[String],
    proposedBy: UUID
)

// The price-tier request workflow (doc 24 §6): request creates a draft agreement; activation is the governed
// maker-checker step (proposer ≠ approver) that flips the agreement AND its tier rules to active — the same
// activation governance as a price-rule change (doc 20 D4). The artifact is a reusable governed agreement, never a
// one-order patch.
final class AgreementService[F[_]: Async](xa: Transactor[F]) {

  def request(req: TierRequest): F[UUID] =
    AgreementRepo
      .insertDraft(req)
      .flatMap(id =>
        req.customers.traverse_(AgreementRepo.addCustomer(id, _)) *>
          req.bands.traverse_(b => AgreementRepo.insertTierRule(id, req.currency, b, req.proposedBy)) *>
          OutboxRepo.append(event(id, "pricing.agreement.requested", req.proposedBy, "draft")).as(id)
      )
      .transact(xa)

  def activate(agreementId: UUID, approver: UUID): F[Either[String, Unit]] =
    AgreementRepo
      .load(agreementId)
      .flatMap {
        case None => "no such agreement".asLeft[Unit].pure[ConnectionIO]
        case Some((status, _)) if status != "draft" =>
          s"agreement is $status, not draft".asLeft[Unit].pure[ConnectionIO]
        case Some((_, Some(proposer))) if proposer == approver =>
          "the proposer cannot approve their own price-tier request (segregation of duties)"
            .asLeft[Unit]
            .pure[ConnectionIO]
        case Some(_) =>
          AgreementRepo.activate(agreementId, approver) *>
            AgreementRepo.activateRules(agreementId, approver) *>
            OutboxRepo
              .append(event(agreementId, "pricing.agreement.activated", approver, "active"))
              .as(().asRight[String])
      }
      .transact(xa)

  private def event(id: UUID, eventType: String, actor: UUID, status: String): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(),
      eventType,
      1,
      "pricing",
      id,
      id.toString,
      None,
      None,
      None,
      Json.obj("agreement_id" -> id.toString.asJson, "status" -> status.asJson, "actor" -> actor.toString.asJson),
      Instant.now(),
      s"user:$actor"
    )
}

object AgreementRepo {

  def insertDraft(req: TierRequest): ConnectionIO[UUID] =
    sql"""INSERT INTO price_agreement
            (name, surface, currency, applies_to, base_volume_basis, valid_from, valid_to, terms, status,
             justification, proposed_by)
          VALUES (${req.name}, 'customer', ${req.currency}, 'customer_set', ${req.baseVolumeBasis},
             ${req.validFrom}, ${req.validTo}, ${req.terms}, 'draft', ${req.justification}, ${req.proposedBy})
          RETURNING id""".query[UUID].unique

  def addCustomer(agreementId: UUID, party: UUID): ConnectionIO[Int] =
    sql"""INSERT INTO price_agreement_customer (agreement_id, party_id) VALUES ($agreementId, $party)
          ON CONFLICT DO NOTHING""".update.run

  def insertTierRule(agreementId: UUID, currency: String, b: TierBand, proposedBy: UUID): ConnectionIO[UUID] =
    sql"""INSERT INTO price_rule
            (surface, product_variant_id, currency, tax_regime, authorised_price, max_discount_pct, min_qty,
             up_to_qty, price_agreement_id, status, owner_user_id)
          VALUES ('customer', ${b.variantId}, $currency, ${b.taxRegime}, ${b.price}, 0, ${b.fromQty},
             ${b.upToQty}, $agreementId, 'draft', $proposedBy)
          RETURNING id""".query[UUID].unique

  def load(id: UUID): ConnectionIO[Option[(String, Option[UUID])]] =
    sql"SELECT status, proposed_by FROM price_agreement WHERE id=$id".query[(String, Option[UUID])].option

  def activate(id: UUID, approver: UUID): ConnectionIO[Int] =
    sql"""UPDATE price_agreement SET status='active', approved_by=$approver, approved_at=now(), updated_at=now()
          WHERE id=$id AND status='draft'""".update.run

  def activateRules(agreementId: UUID, approver: UUID): ConnectionIO[Int] =
    sql"""UPDATE price_rule SET status='active', approved_by=$approver, updated_at=now()
          WHERE price_agreement_id=$agreementId AND status='draft'""".update.run
}
