package com.hypervolt.conduit.pricing

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.OutboxEvent
import com.hypervolt.conduit.event.OutboxRepo
import com.hypervolt.conduit.ledger.Journal
import com.hypervolt.conduit.ledger.JournalAccount
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.Posting
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// ConnectionIO reads for the rebate projection (doc 24 §5.2) — all over the immutable order/agreement facts.
object RebateRepo {

  def validFrom(agreementId: UUID): ConnectionIO[Option[Instant]] =
    sql"SELECT valid_from FROM price_agreement WHERE id = $agreementId".query[Instant].option

  // The contract's committed annual volume (doc 24 §5.3) — the H6Q floor recorded as a descriptive term. None when
  // the contract carries no commitment (the ASC-606 constraint then collapses expected to earned).
  def commitment(agreementId: UUID): ConnectionIO[Option[Int]] =
    sql"SELECT (terms->>'min_commitment_units')::int FROM price_agreement WHERE id = $agreementId"
      .query[Option[Int]]
      .option
      .map(_.flatten)

  // Discrete settlements already approved for the contract year — the draw-downs the outstanding target nets off.
  def settledTotal(agreementId: UUID, yearIndex: Int): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(amount), 0) FROM rebate_settlement
          WHERE agreement_id = $agreementId AND contract_year_index = $yearIndex AND status = 'approved'"""
      .query[BigDecimal]
      .unique

  // The distinct (variant, product_class) the agreement prices — the rebate is computed per variant.
  def variantClasses(agreementId: UUID): ConnectionIO[List[(UUID, String)]] =
    sql"""SELECT DISTINCT pr.product_variant_id, pv.product_class
          FROM price_rule pr JOIN product_variant pv ON pv.id = pr.product_variant_id
          WHERE pr.price_agreement_id = $agreementId AND pr.surface = 'customer'
            AND pr.product_variant_id IS NOT NULL"""
      .query[(UUID, String)]
      .to[List]

  // The tier ladder (threshold, price) for a variant under the agreement.
  def ladder(agreementId: UUID, variantId: UUID): ConnectionIO[List[(Int, BigDecimal)]] =
    sql"""SELECT min_qty, authorised_price FROM price_rule
          WHERE price_agreement_id = $agreementId AND product_variant_id = $variantId AND surface = 'customer'
          ORDER BY min_qty"""
      .query[(Int, BigDecimal)]
      .to[List]

  // Units of a variant invoiced in the window, across the agreement's whole customer set (group aggregation).
  def variantUnits(agreementId: UUID, variantId: UUID, start: Instant, end: Instant): ConnectionIO[Int] =
    sql"""SELECT COALESCE(SUM(ol.qty), 0)::int
          FROM order_line ol JOIN "order" o ON o.id = ol.order_id
          WHERE o.sold_to_party_id IN (SELECT party_id FROM price_agreement_customer WHERE agreement_id = $agreementId)
            AND ol.product_variant_id = $variantId
            AND o.status NOT IN ('cancelled', 'pending_ceo', 'draft')
            AND o.created_at >= $start AND o.created_at < $end"""
      .query[Int]
      .unique

  def loadSettlement(
      id: UUID
  ): ConnectionIO[Option[(UUID, Int, String, UUID, BigDecimal, String, String, Option[UUID])]] =
    sql"""SELECT agreement_id, contract_year_index, milestone, entity_id, amount, currency, status, proposed_by
          FROM rebate_settlement WHERE id = $id"""
      .query[(UUID, Int, String, UUID, BigDecimal, String, String, Option[UUID])]
      .option

  def insertProposal(
      agreementId: UUID,
      yearIndex: Int,
      milestone: String,
      entity: UUID,
      amount: BigDecimal,
      currency: String,
      proposedBy: UUID
  ): ConnectionIO[Option[UUID]] =
    sql"""INSERT INTO rebate_settlement
            (agreement_id, contract_year_index, milestone, entity_id, amount, currency, status, proposed_by)
          VALUES ($agreementId, $yearIndex, $milestone, $entity, $amount, $currency, 'proposed', $proposedBy)
          ON CONFLICT (agreement_id, contract_year_index, milestone) DO NOTHING
          RETURNING id""".query[UUID].option

  def markApproved(id: UUID, approver: UUID): ConnectionIO[Int] =
    sql"""UPDATE rebate_settlement SET status = 'approved', approved_by = $approver, approved_at = now()
          WHERE id = $id AND status = 'proposed'""".update.run
}

// The retrospective volume-rebate engine (doc 24 §5). ACCRUE: the earned rebate is a reproducible projection over
// the contract year's orders + tier ladder; accrual brings the REBATE_ACCRUAL ledger balance up to it (true-up
// delta — idempotent, monotonic within the year). APPLY/SETTLE: a SEPARATE, maker-checker-governed, idempotent act
// that draws the accrual down (a rebate payment). There is NO code path that settles without the maker-checker step.
final class RebateService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val journal = new Journal[F](xa, ledger)

  def rebateAccrual(entity: UUID): BigInt = TbIds.accountId(s"REBATE_ACCRUAL:$entity")
  def revenue(entity: UUID): BigInt       = TbIds.accountId(s"REVENUE:$entity")
  def bank(entity: UUID): BigInt          = TbIds.accountId(s"BANK:$entity")

  private def minor(a: BigDecimal): BigInt             = (a.setScale(2, RoundingMode.HALF_UP) * 100).toBigInt
  private def detId(s: String): UUID                   = UUID.nameUUIDFromBytes(s.getBytes(StandardCharsets.UTF_8))
  private def yearIndex(vf: Instant, at: Instant): Int = ContractYear.indexOf(vf, at).toInt

  // The reproducible earned-rebate projection for an agreement as-of an instant (doc 24 §5.2): the rebate owed at
  // the tier the ACTUAL cumulative volume has achieved.
  def earnedRebate(agreementId: UUID, asOf: Instant): F[BigDecimal] =
    projection(agreementId, asOf, (cumVol, _) => cumVol).transact(xa)

  // The EXPECTED rebate (doc 24 §5.3, ASC 606): the same projection at the expected FINAL tier — the larger of the
  // actual volume and the contract commitment (the H6Q floor). Constrained: with no commitment, expected == earned
  // (recognise only consideration highly likely not to reverse). Converges to earned as actual volume lands.
  def expectedRebate(agreementId: UUID, asOf: Instant): F[BigDecimal] =
    projection(agreementId, asOf, (cumVol, commitment) => math.max(cumVol, commitment.getOrElse(0))).transact(xa)

  private def projection(
      agreementId: UUID,
      asOf: Instant,
      position: (Int, Option[Int]) => Int
  ): ConnectionIO[BigDecimal] =
    RebateRepo.validFrom(agreementId).flatMap {
      case None => BigDecimal(0).pure[ConnectionIO]
      case Some(vf) =>
        val (start, end) = ContractYear.windowFor(vf, asOf)
        RebateRepo.commitment(agreementId).flatMap { commitment =>
          RebateRepo
            .variantClasses(agreementId)
            .flatMap(
              _.traverse {
                case (variant, cls) =>
                  (
                    RebateRepo.ladder(agreementId, variant),
                    ContractVolumeRepo.priorCumulativeQualifying(agreementId, cls, start, end),
                    RebateRepo.variantUnits(agreementId, variant, start, end)
                  ).mapN { (ladder, cumVol, units) =>
                    RebateEngine.earnedAt(
                      ladder.map { case (q, p) => RebateEngine.Tier(q, p) },
                      position(cumVol, commitment),
                      units
                    )
                  }
              }.map(_.foldLeft(BigDecimal(0))(_ + _))
            )
        }
    }

  // ACCRUE (doc 24 §5.2/§5.5): bring REBATE_ACCRUAL gross credits up to the earned amount via a true-up delta —
  // DR REVENUE / CR REBATE_ACCRUAL. Idempotent: re-running at the same earned posts a zero delta (dropped); growth
  // posts the increment. The delta transfer id is keyed by the earned target so a re-post is a TB no-op.
  def accrue(agreementId: UUID, entity: UUID, ccy: Currency, asOf: Instant): F[Unit] =
    earnedRebate(agreementId, asOf).flatMap { earned =>
      RebateRepo.validFrom(agreementId).transact(xa).flatMap {
        case None => Async[F].unit
        case Some(vf) =>
          val ledgerId = Ledgers.forCurrency(ccy)
          ledger.createAccounts(
            List(
              LedgerAccount(rebateAccrual(entity), ledgerId, LedgerAccountCode.RebateAccrual),
              LedgerAccount(revenue(entity), ledgerId, LedgerAccountCode.Revenue)
            )
          ) *> ledger.balance(rebateAccrual(entity)).flatMap { bal =>
            val target = minor(earned)
            val delta  = target - bal.creditsPosted // gross accrued so far = credits to the liability
            if (delta <= 0) Async[F].unit
            else {
              val yr = yearIndex(vf, asOf)
              val id = detId(s"rebate-accrue:$agreementId:$yr:$target")
              journal.postOne(
                asOf,
                Posting(
                  id,
                  0,
                  JournalAccount(s"REVENUE:$entity", LedgerAccountCode.Revenue, Some(entity)),
                  JournalAccount(s"REBATE_ACCRUAL:$entity", LedgerAccountCode.RebateAccrual, Some(entity)),
                  ccy,
                  delta
                )
              ) *> (claimPosting(agreementId, "accrue", id) *>
                emit(agreementId, "pricing.rebate.accrued", Json.obj("earned" -> earned.toString.asJson)))
                .transact(xa)
                .void
            }
          }
      }
    }

  // ACCRUE at the EXPECTED final tier (doc 24 §5.3) — recognition net-of-expected as a true-up: bring the
  // OUTSTANDING liability (credits − debits) to `expected − settled`. Bidirectional: growth posts an accrual
  // (DR REVENUE / CR REBATE_ACCRUAL); a drop in the estimate posts a RELEASE (DR REBATE_ACCRUAL / CR REVENUE) —
  // posted entries are never reopened, the delta is a current-period adjustment (the M5 true-up pattern on the
  // revenue side). Idempotent per ledger state: the transfer id is keyed by the exact (credits, debits) the delta
  // was computed from, so a re-run from the same state is a TB no-op, and any prior posting changed the state.
  def accrueExpected(agreementId: UUID, entity: UUID, ccy: Currency, asOf: Instant): F[Unit] =
    expectedRebate(agreementId, asOf).flatMap { expected =>
      RebateRepo.validFrom(agreementId).transact(xa).flatMap {
        case None => Async[F].unit
        case Some(vf) =>
          val yr       = yearIndex(vf, asOf)
          val ledgerId = Ledgers.forCurrency(ccy)
          ledger.createAccounts(
            List(
              LedgerAccount(rebateAccrual(entity), ledgerId, LedgerAccountCode.RebateAccrual),
              LedgerAccount(revenue(entity), ledgerId, LedgerAccountCode.Revenue)
            )
          ) *> RebateRepo.settledTotal(agreementId, yr).transact(xa).flatMap { settled =>
            ledger.balance(rebateAccrual(entity)).flatMap { bal =>
              val outstanding = bal.creditsPosted - bal.debitsPosted
              val target      = (minor(expected) - minor(settled)).max(BigInt(0))
              val delta       = target - outstanding
              val id          = detId(s"rebate-trueup:$agreementId:$yr:${bal.creditsPosted}:${bal.debitsPosted}:$target")
              val accrualAcc  = JournalAccount(s"REBATE_ACCRUAL:$entity", LedgerAccountCode.RebateAccrual, Some(entity))
              val revenueAcc  = JournalAccount(s"REVENUE:$entity", LedgerAccountCode.Revenue, Some(entity))
              if (delta == 0) Async[F].unit
              else if (delta > 0)
                journal.postOne(asOf, Posting(id, 0, revenueAcc, accrualAcc, ccy, delta)) *>
                  (claimPosting(agreementId, "trueup_up", id) *>
                    emit(agreementId, "pricing.rebate.accrued", Json.obj("expected" -> expected.toString.asJson)))
                    .transact(xa)
                    .void
              else
                journal.postOne(asOf, Posting(id, 0, accrualAcc, revenueAcc, ccy, -delta)) *>
                  (claimPosting(agreementId, "trueup_down", id) *>
                    emit(agreementId, "pricing.rebate.trued_up", Json.obj("expected" -> expected.toString.asJson)))
                    .transact(xa)
                    .void
            }
          }
      }
    }

  // Propose a settlement (maker step). Idempotent at the business level via UNIQUE(agreement, year, milestone) —
  // a re-proposal returns the existing row, never a duplicate.
  def proposeSettlement(
      agreementId: UUID,
      entity: UUID,
      ccy: Currency,
      milestone: String,
      asOf: Instant,
      proposedBy: UUID
  ): F[Either[String, UUID]] =
    earnedRebate(agreementId, asOf).flatMap { earned =>
      RebateRepo.validFrom(agreementId).transact(xa).flatMap {
        case None => "no such agreement".asLeft[UUID].pure[F]
        case Some(vf) =>
          val yr = yearIndex(vf, asOf)
          RebateRepo
            .insertProposal(agreementId, yr, milestone, entity, earned, ccy.code, proposedBy)
            .transact(xa)
            .flatMap {
              case Some(id) => id.asRight[String].pure[F]
              case None =>
                existingProposal(agreementId, yr, milestone)
                  .transact(xa)
                  .map(_.toRight("could not create or find proposal"))
            }
      }
    }

  private def existingProposal(agreementId: UUID, yr: Int, milestone: String): ConnectionIO[Option[UUID]] =
    sql"""SELECT id FROM rebate_settlement
          WHERE agreement_id = $agreementId AND contract_year_index = $yr AND milestone = $milestone"""
      .query[UUID]
      .option

  // APPLY/SETTLE (doc 24 §5.4) — the checker step. Maker-checker (approver ≠ proposer); draws the accrual down:
  // DR REBATE_ACCRUAL / CR BANK. Idempotent: the transfer id is deterministic per (agreement, year, milestone), so a
  // re-approval is a TB no-op (credits once). Emits pricing.rebate.settled.
  def approveSettlement(settlementId: UUID, approver: UUID): F[Either[String, Unit]] =
    RebateRepo.loadSettlement(settlementId).transact(xa).flatMap {
      case None => "no such settlement".asLeft[Unit].pure[F]
      case Some((_, _, _, _, _, _, status, _)) if status != "proposed" =>
        s"settlement is $status, not proposed".asLeft[Unit].pure[F]
      case Some((_, _, _, _, _, _, _, Some(proposer))) if proposer == approver =>
        "the proposer cannot approve their own rebate settlement (segregation of duties)".asLeft[Unit].pure[F]
      case Some((agreementId, yr, milestone, entity, amount, currency, _, _)) =>
        Currency.fromCode(currency) match {
          case None => s"unknown currency $currency".asLeft[Unit].pure[F]
          case Some(ccy) =>
            val ledgerId = Ledgers.forCurrency(ccy)
            val id       = detId(s"rebate-settle:$agreementId:$yr:$milestone")
            ledger.createAccounts(
              List(
                LedgerAccount(rebateAccrual(entity), ledgerId, LedgerAccountCode.RebateAccrual),
                LedgerAccount(bank(entity), ledgerId, LedgerAccountCode.Bank)
              )
            ) *> journal.postOne(
              Instant.now(),
              Posting(
                id,
                0,
                JournalAccount(s"REBATE_ACCRUAL:$entity", LedgerAccountCode.RebateAccrual, Some(entity)),
                JournalAccount(s"BANK:$entity", LedgerAccountCode.Bank, Some(entity)),
                ccy,
                minor(amount)
              )
            ) *> (RebateRepo.markApproved(settlementId, approver) *>
              claimPosting(agreementId, "settle", id) *>
              emit(agreementId, "pricing.rebate.settled", Json.obj("amount" -> amount.toString.asJson)))
              .transact(xa)
              .as(().asRight[String])
        }
    }

  // Rebate ids are deterministic (TB no-op on re-run) but were never persisted — reproducible-by-design is
  // not traceable-by-SQL. The claim row lets CTRL-LINEAGE-CLOSURE walk each posted movement back here.
  private def claimPosting(agreementId: UUID, kind: String, eventId: UUID): ConnectionIO[Int] =
    sql"""INSERT INTO rebate_posting (tb_transfer_id, agreement_id, kind)
          VALUES (${BigDecimal(TbIds.transferId(eventId, 0))}, $agreementId, $kind)
          ON CONFLICT (tb_transfer_id) DO NOTHING""".update.run

  private def emit(agreementId: UUID, eventType: String, payload: Json): ConnectionIO[Int] =
    OutboxRepo.append(
      OutboxEvent(
        UUID.randomUUID(),
        eventType,
        1,
        "pricing",
        agreementId,
        agreementId.toString,
        None,
        None,
        None,
        payload.deepMerge(Json.obj("agreement_id" -> agreementId.toString.asJson)),
        Instant.now(),
        "service:rebate"
      )
    )
}
