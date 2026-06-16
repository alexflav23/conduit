package com.hypervolt.conduit.treasury

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.util.transactor.Transactor
import java.time.LocalDate
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

final case class RequiredHedge(
    exposureType: String,
    exposureUsd: BigDecimal,
    ratio: BigDecimal,
    requiredUsd: BigDecimal
)
final case class Coverage(exposureUsd: BigDecimal, hedgedUsd: BigDecimal, ratio: BigDecimal)

// The FX hedging program (M12-Treasury): size the required hedge from forecast exposure × the policy ratio, run the
// over/under-hedge coverage check, and govern each contract through a multi-party named approval (maker ≠ checker)
// before execution. Apply-to-COGS, margin-call monitoring, maturity extension and effectiveness reporting build on
// top of this in subsequent slices.
final class HedgeProgramService[F[_]: Async](xa: Transactor[F]) {

  // Regenerate the exposure forecast from the current forecast × supplier cost (Volex→Luxshare at `transition`).
  def rebuildExposureForecast(entityId: UUID, transition: LocalDate): F[Int] =
    HedgeProgramRepo.rebuildExposureForecast(entityId, transition).transact(xa)

  // Recompute + read the effectiveness stream (hedged vs counterfactual all-spot — the economic hedge contribution).
  def rebuildEffectiveness(entityId: UUID): F[Int]             = HedgeProgramRepo.rebuildEffectiveness(entityId).transact(xa)
  def effectiveness(entityId: UUID): F[List[EffectivenessRow]] = HedgeProgramRepo.effectiveness(entityId).transact(xa)

  // Required hedged USD by exposure type = Σ exposure × the policy ratio in force (the 50 / 50 / 100 policy).
  def requiredHedge(entityId: UUID, from: LocalDate, to: LocalDate, asOf: LocalDate): F[List[RequiredHedge]] =
    (HedgeProgramRepo.exposures(entityId, from, to), HedgeProgramRepo.policies(entityId, asOf)).tupled
      .transact(xa)
      .map {
        case (exps, pols) =>
          val ratioOf = pols.map(p => p.exposureType -> p.hedgeRatio).toMap
          exps.groupBy(_.exposureType).toList.map {
            case (et, rows) =>
              val exposure = rows.map(_.amountUsd).sum
              val ratio    = ratioOf.getOrElse(et, BigDecimal(0))
              RequiredHedge(et, exposure, ratio, (exposure * ratio).setScale(2, RoundingMode.HALF_UP))
          }
      }

  // The hedge ratio actually in force: open contract notional vs forecast exposure (the policy doc's over/under-
  // hedge check — e.g. Contract 3 at 142% vs the 50% policy flags an overhedge).
  def coverage(entityId: UUID, from: LocalDate, to: LocalDate): F[Coverage] =
    (HedgeProgramRepo.exposures(entityId, from, to), HedgeProgramRepo.contracts(entityId)).tupled.transact(xa).map {
      case (exps, cons) =>
        val exposure = exps.map(_.amountUsd).sum
        val hedged = cons
          .filter(c => HedgeStatus.Open(c.status))
          .filter(c => !c.validTo.isBefore(from) && c.validFrom.isBefore(to))
          .map(_.notionalOpen)
          .sum
        Coverage(
          exposure,
          hedged,
          if (exposure > 0) (hedged / exposure).setScale(4, RoundingMode.HALF_UP)
          else BigDecimal(0)
        )
    }

  // Propose a hedge contract: created 'proposed', with a pending approval per required board role (maker-checker —
  // the named roles sign before execution, then the provider terms are accepted).
  def propose(entityId: UUID, n: NewHedgeContract, requiredRoles: List[String], createdBy: Option[UUID]): F[UUID] =
    HedgeProgramRepo
      .insertContract(entityId, n, HedgeStatus.Proposed, createdBy)
      .flatMap(id => requiredRoles.traverse_(r => HedgeProgramRepo.addApproval(id, "execute", r, None)).as(id))
      .transact(xa)

  // Sign one approval; when every 'execute' approval is signed the contract becomes 'approved'.
  def sign(approvalId: UUID, hedgeId: UUID, userId: UUID): F[Either[String, String]] =
    HedgeProgramRepo
      .sign(approvalId, userId)
      .flatMap {
        case 0 => (Left("approval not pending"): Either[String, String]).pure[ConnectionIO]
        case _ =>
          HedgeProgramRepo.approvals(hedgeId, "execute").flatMap { aps =>
            if (aps.nonEmpty && aps.forall(_.status == "signed"))
              HedgeProgramRepo.setStatus(hedgeId, HedgeStatus.Approved).as(Right("approved"): Either[String, String])
            else (Right("signed"): Either[String, String]).pure[ConnectionIO]
          }
      }
      .transact(xa)

  // Record the provider's accepted terms (booked rate) and mark the contract executed.
  def execute(hedgeId: UUID, bookedRate: BigDecimal, validTo: LocalDate): F[Either[String, Unit]] =
    HedgeProgramRepo
      .setRateAndMaturity(hedgeId, bookedRate, validTo)
      .flatMap(_ => HedgeProgramRepo.setStatus(hedgeId, HedgeStatus.Executed))
      .transact(xa)
      .map {
        case 0 => Left("contract not found")
        case _ => Right(())
      }
}
