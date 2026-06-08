package com.hypervolt.conduit.tax

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
import java.time.LocalDate
import java.util.UUID

final case class NewRate(
    taxType: String,
    jurisdiction: String,
    region: Option[String],
    postcodePrefix: Option[String],
    level: String,
    taxCategoryCode: Option[String],
    name: String,
    ratePct: BigDecimal,
    kind: String,
    effectiveFrom: LocalDate
)

private final case class DraftRate(
    taxType: String,
    jurisdiction: String,
    region: Option[String],
    postcodePrefix: Option[String],
    level: String,
    category: Option[String],
    ratePct: BigDecimal,
    effectiveFrom: LocalDate,
    status: String,
    proposedBy: Option[UUID]
)

// Governed rate changes (doc 16 §7/§9): a rate is added as a draft (maker), then ACTIVATED by a different
// approver (checker — proposer ≠ approver). Activation never edits in place — it closes the prior active row's
// effective window at the new from-date and opens the new one, so the rate in force at any historic as_of stays
// reproducible. Emits tax.regime.changed (audited).
final class TaxRateService[F[_]: Async](xa: Transactor[F]) {

  def propose(r: NewRate, proposer: UUID): F[UUID] = {
    val id = UUID.randomUUID()
    sql"""INSERT INTO tax_rate
            (id, tax_type, jurisdiction, region, postcode_prefix, level, tax_category_code, name, rate_pct, kind,
             effective_from, status, proposed_by)
          VALUES ($id, ${r.taxType}, ${r.jurisdiction}, ${r.region}, ${r.postcodePrefix}, ${r.level},
             ${r.taxCategoryCode}, ${r.name}, ${r.ratePct}, ${r.kind}, ${r.effectiveFrom}, 'draft', $proposer)""".update.run
      .transact(xa)
      .as(id)
  }

  def activate(id: UUID, approver: UUID): F[Either[String, Unit]] =
    loadDraft(id)
      .flatMap {
        case None                                       => leftC("rate not found")
        case Some(d) if d.status != "draft"             => leftC(s"rate is ${d.status}, not draft")
        case Some(d) if d.proposedBy.contains(approver) => leftC("proposer cannot self-approve")
        case Some(d) =>
          (closePrior(id, d) *> markActive(id, approver) *> OutboxRepo.append(changedEvent(id, d, approver))).map(_ =>
            ().asRight[String]
          )
      }
      .transact(xa)

  private def loadDraft(id: UUID): ConnectionIO[Option[DraftRate]] =
    sql"""SELECT tax_type, jurisdiction, region, postcode_prefix, level, tax_category_code, rate_pct, effective_from,
            status, proposed_by
          FROM tax_rate WHERE id = $id"""
      .query[DraftRate]
      .option

  // Effective-date supersession: the prior active row for the same key is closed at the new from-date.
  private def closePrior(id: UUID, d: DraftRate): ConnectionIO[Int] =
    sql"""UPDATE tax_rate
          SET effective_to = ${d.effectiveFrom}, status = 'superseded', updated_at = now()
          WHERE id <> $id AND status = 'active'
            AND tax_type = ${d.taxType} AND jurisdiction = ${d.jurisdiction} AND level = ${d.level}
            AND region IS NOT DISTINCT FROM ${d.region}
            AND postcode_prefix IS NOT DISTINCT FROM ${d.postcodePrefix}
            AND tax_category_code IS NOT DISTINCT FROM ${d.category}
            AND (effective_to IS NULL OR effective_to > ${d.effectiveFrom})""".update.run

  private def markActive(id: UUID, approver: UUID): ConnectionIO[Int] =
    sql"UPDATE tax_rate SET status = 'active', approved_by = $approver, updated_at = now() WHERE id = $id".update.run

  private def changedEvent(id: UUID, d: DraftRate, approver: UUID): OutboxEvent =
    OutboxEvent(
      UUID.randomUUID(),
      "tax.regime.changed",
      1,
      "tax",
      id,
      s"${d.jurisdiction}:${d.taxType}",
      None,
      None,
      None,
      Json.obj(
        "tax_rate_id"    -> id.toString.asJson,
        "tax_type"       -> d.taxType.asJson,
        "jurisdiction"   -> d.jurisdiction.asJson,
        "region"         -> d.region.asJson,
        "level"          -> d.level.asJson,
        "rate_pct"       -> d.ratePct.asJson,
        "effective_from" -> d.effectiveFrom.toString.asJson,
        "approved_by"    -> approver.toString.asJson
      ),
      Instant.now(),
      "service:tax"
    )

  private def leftC(msg: String): ConnectionIO[Either[String, Unit]] = msg.asLeft[Unit].pure[ConnectionIO]
}
