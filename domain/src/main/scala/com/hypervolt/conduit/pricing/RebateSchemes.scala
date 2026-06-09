package com.hypervolt.conduit.pricing

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import java.time.Instant
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// A generalised rebate scheme (doc 24 §4.4) — any time-bound rebate, over its OWN window/basis, with a product-class
// qualifying set (whose units accumulate toward the tier) and an applies set (which products receive it, §4.5).
final case class RebateScheme(
    id: UUID,
    basis: String,
    unit: String,
    qualifyingClasses: List[String],
    appliesClasses: List[String],
    ladder: List[RebateSchemeEngine.Rung],
    validFrom: Instant,
    validTo: Option[Instant]
)

// Pure scheme math (doc 24 §4.4). `valueAt` is the rung the position has reached (0 below all). volume → a per-unit
// rebate at the qualifying tier applied to the receiving units; flat → a percentage of the receiving spend.
object RebateSchemeEngine {
  final case class Rung(fromThreshold: BigDecimal, value: BigDecimal)

  def valueAt(ladder: List[Rung], position: BigDecimal): BigDecimal =
    ladder.filter(_.fromThreshold <= position).sortBy(_.fromThreshold).lastOption.map(_.value).getOrElse(BigDecimal(0))

  def earnedVolume(ladder: List[Rung], qualifyingVolume: BigDecimal, unitsReceiving: BigDecimal): BigDecimal =
    (unitsReceiving * valueAt(ladder, qualifyingVolume)).max(BigDecimal(0))

  def earnedFlat(ladder: List[Rung], spendReceiving: BigDecimal): BigDecimal =
    (spendReceiving * valueAt(ladder, BigDecimal(0)) / 100).max(BigDecimal(0))
}

object RebateSchemeRepo {

  private def classes(j: Json): List[String] =
    j.hcursor.get[List[String]]("product_class").toOption.getOrElse(Nil)

  private def rungs(j: Json): List[RebateSchemeEngine.Rung] =
    j.asArray
      .getOrElse(Vector.empty)
      .toList
      .flatMap { r =>
        (r.hcursor.get[BigDecimal]("from_threshold").toOption, r.hcursor.get[BigDecimal]("value").toOption)
          .mapN(RebateSchemeEngine.Rung.apply)
      }

  def activeSchemes(agreementId: UUID, asOf: Instant): ConnectionIO[List[RebateScheme]] =
    sql"""SELECT id, basis, unit, qualifying_filter, applies_filter, ladder, valid_from, valid_to
          FROM rebate_scheme
          WHERE agreement_id = $agreementId AND status = 'active'
            AND valid_from <= $asOf AND (valid_to IS NULL OR valid_to > $asOf)"""
      .query[(UUID, String, String, Json, Json, Json, Instant, Option[Instant])]
      .to[List]
      .map(_.map {
        case (id, basis, unit, qf, af, ladder, vf, vt) =>
          RebateScheme(id, basis, unit, classes(qf), classes(af), rungs(ladder), vf, vt)
      })

  // Qualifying units across the agreement's whole customer set, restricted to the qualifying product classes, in
  // the scheme window. Empty class filter ⇒ all classes.
  def qualifyingVolume(
      agreementId: UUID,
      qualifyingClasses: List[String],
      start: Instant,
      end: Instant
  ): ConnectionIO[BigDecimal] =
    units(agreementId, qualifyingClasses, start, end)

  def appliesUnits(
      agreementId: UUID,
      appliesClasses: List[String],
      start: Instant,
      end: Instant
  ): ConnectionIO[BigDecimal] =
    units(agreementId, appliesClasses, start, end)

  def appliesSpend(
      agreementId: UUID,
      appliesClasses: List[String],
      start: Instant,
      end: Instant
  ): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(ol.qty * ol.unit_price_ex_vat), 0)
          FROM order_line ol JOIN "order" o ON o.id = ol.order_id JOIN product_variant pv ON pv.id = ol.product_variant_id
          WHERE o.sold_to_party_id IN (SELECT party_id FROM price_agreement_customer WHERE agreement_id = $agreementId)
            AND (${appliesClasses.isEmpty} OR pv.product_class = ANY($appliesClasses))
            AND o.status NOT IN ('cancelled', 'pending_ceo', 'draft')
            AND o.created_at >= $start AND o.created_at < $end"""
      .query[BigDecimal]
      .unique

  private def units(agreementId: UUID, cls: List[String], start: Instant, end: Instant): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(ol.qty), 0)
          FROM order_line ol JOIN "order" o ON o.id = ol.order_id JOIN product_variant pv ON pv.id = ol.product_variant_id
          WHERE o.sold_to_party_id IN (SELECT party_id FROM price_agreement_customer WHERE agreement_id = $agreementId)
            AND (${cls.isEmpty} OR pv.product_class = ANY($cls))
            AND o.status NOT IN ('cancelled', 'pending_ceo', 'draft')
            AND o.created_at >= $start AND o.created_at < $end"""
      .query[BigDecimal]
      .unique
}

// Evaluates every active rebate scheme on an agreement, each over its OWN window (doc 24 §4.4), and sums the earned
// rebate — the additive, generalised counterpart to the base-tier retrospective rebate (RebateService §5). Same
// accrue/settle discipline applies downstream.
final class RebateSchemeService[F[_]: Async](xa: Transactor[F]) {

  def earnedSchemes(agreementId: UUID, asOf: Instant): F[BigDecimal] =
    RebateSchemeRepo
      .activeSchemes(agreementId, asOf)
      .flatMap(_.traverse(s => earnedOne(agreementId, s, asOf)).map(_.foldLeft(BigDecimal(0))(_ + _)))
      .transact(xa)
      .map(_.setScale(2, RoundingMode.HALF_UP))

  private def earnedOne(agreementId: UUID, s: RebateScheme, asOf: Instant): ConnectionIO[BigDecimal] = {
    val end = s.validTo.getOrElse(asOf)
    s.basis match {
      case "flat" =>
        RebateSchemeRepo
          .appliesSpend(agreementId, s.appliesClasses, s.validFrom, end)
          .map(RebateSchemeEngine.earnedFlat(s.ladder, _))
      case _ => // volume (unit) — the general per-unit tier rebate
        (
          RebateSchemeRepo.qualifyingVolume(agreementId, s.qualifyingClasses, s.validFrom, end),
          RebateSchemeRepo.appliesUnits(agreementId, s.appliesClasses, s.validFrom, end)
        ).mapN((qual, applies) => RebateSchemeEngine.earnedVolume(s.ladder, qual, applies))
    }
  }
}
