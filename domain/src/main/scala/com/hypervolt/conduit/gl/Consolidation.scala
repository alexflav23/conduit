package com.hypervolt.conduit.gl

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.ledger.LedgerAccountCode
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// One translated account on a consolidation run — the native-currency balance plus the provenanced rate (hedge or
// closing/spot) it was translated at, so the presentation figure re-derives exactly.
final case class ConsLine(
    entity: UUID,
    accountKey: String,
    role: Int,
    rateClass: String,
    functionalCurrency: String,
    balanceFunctional: BigDecimal,
    rate: BigDecimal,
    rateSource: String,
    exchangeRateId: Option[UUID],
    fxHedgeId: Option[UUID],
    balancePresentation: BigDecimal
)

object ConsolidationRepo {

  def entitiesWithActivity: ConnectionIO[List[(UUID, String)]] =
    sql"""SELECT DISTINCT e.id, e.functional_currency FROM gl_entry g JOIN entity e ON e.id = g.entity_id
          WHERE g.posted = true ORDER BY e.id""".query[(UUID, String)].to[List]

  // Hedge-locked rate if a designated hedge covers asOf, else the closing/spot rate, else identity (a gap the
  // CTRL-FXRATE-COMPLETE control catches). Returns (rate, source, exchange_rate_id, fx_hedge_id).
  def resolveRate(
      from: String,
      to: String,
      asOf: LocalDate
  ): ConnectionIO[(BigDecimal, String, Option[UUID], Option[UUID])] =
    if (from == to) (BigDecimal(1), "identity", Option.empty[UUID], Option.empty[UUID]).pure[ConnectionIO]
    else
      hedge(from, to, asOf).flatMap {
        case Some((hid, rate)) => (rate, s"hedge:$hid", Option.empty[UUID], hid.some).pure[ConnectionIO]
        case None =>
          closingRate(from, to, asOf).map {
            case Some((rid, r, rt)) => (r, rt, rid.some, Option.empty[UUID])
            case None               => (BigDecimal(1), "identity", Option.empty[UUID], Option.empty[UUID])
          }
      }

  private def hedge(from: String, to: String, asOf: LocalDate): ConnectionIO[Option[(UUID, BigDecimal)]] =
    sql"""SELECT id, contracted_rate FROM fx_hedge
          WHERE pair_from = $from AND pair_to = $to AND status = 'active' AND valid_from <= $asOf AND valid_to >= $asOf
          ORDER BY valid_from DESC LIMIT 1""".query[(UUID, BigDecimal)].option

  private def closingRate(from: String, to: String, asOf: LocalDate): ConnectionIO[Option[(UUID, BigDecimal, String)]] =
    sql"""SELECT id, rate, rate_type FROM exchange_rate
          WHERE base = $from AND quote = $to AND rate_type IN ('closing','spot') AND as_of <= $asOf
          ORDER BY as_of DESC, (rate_type = 'closing') DESC LIMIT 1""".query[(UUID, BigDecimal, String)].option

  def insertRun(
      asOf: LocalDate,
      presentation: String,
      assets: BigDecimal,
      liabilities: BigDecimal,
      equity: BigDecimal,
      cta: BigDecimal,
      fxResidual: BigDecimal,
      balanced: Boolean,
      runBy: Option[UUID]
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO consolidation_run
            (as_of, presentation_currency, total_assets, total_liabilities, total_equity, cta, fx_clearing_residual, balanced, run_by)
          VALUES ($asOf, $presentation, $assets, $liabilities, $equity, $cta, $fxResidual, $balanced, $runBy)
          RETURNING id""".query[UUID].unique

  def insertLine(runId: UUID, l: ConsLine): ConnectionIO[Int] =
    sql"""INSERT INTO consolidation_line
            (run_id, entity_id, account_key, account_role, rate_class, functional_currency, balance_functional,
             rate, rate_source, exchange_rate_id, fx_hedge_id, balance_presentation)
          VALUES ($runId, ${l.entity}, ${l.accountKey}, ${l.role}, ${l.rateClass}, ${l.functionalCurrency},
             ${l.balanceFunctional}, ${l.rate}, ${l.rateSource}, ${l.exchangeRateId}, ${l.fxHedgeId}, ${l.balancePresentation})""".update.run

  // Record a control outcome that is computed (not a SQL evidence_query) — the translation-integrity controls.
  def recordControl(code: String, result: String, detail: Json, periodId: Option[UUID]): ConnectionIO[Int] =
    sql"""INSERT INTO control_run (control_id, result, detail, period_id)
          SELECT id, $result, $detail, $periodId FROM control WHERE code = $code AND status = 'active'""".update.run
}

// Consolidation / translation (ASC 830, doc 14 §2.4 / doc 13 §7.2) over the gl_entry MIRROR. Each entity's native
// as-of balances are translated to the presentation currency at a provenanced rate — hedge-locked where a hedge is
// designated, else the closing/spot rate — and written as an IMMUTABLE consolidation_run + lines (which rate / which
// hedge per line), so the consolidated figure re-derives exactly. CTA is the cross-entity translation residual; the
// FX_CLEARING bridge should net to ~zero. Both are re-performed as controls. Native facts are never translated in
// storage — only here, on demand (a re-projection, like fiscal period assignment).
final class ConsolidationService[F[_]: Async](xa: Transactor[F]) {

  import LedgerAccountCode._

  private def asOfInstant(d: LocalDate) = d.atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC)

  private def rateClass(role: Int): String =
    if (role == Inv || role == OpeningEquity || role == InvWriteOff) "non_monetary"
    else if (
      role == Revenue || role == CosClearing || role == FeeExpense || role == CarriageExpense ||
      role == CommissionExpense || role == IcMargin
    ) "pnl"
    else "monetary"

  def run(asOf: LocalDate, presentation: String, runBy: Option[UUID]): F[Json] =
    program(asOf, presentation, runBy).transact(xa)

  private def program(asOf: LocalDate, presentation: String, runBy: Option[UUID]): ConnectionIO[Json] =
    ConsolidationRepo.entitiesWithActivity
      .flatMap(_.flatTraverse {
        case (entity, fc) =>
          ConsolidationRepo.resolveRate(fc, presentation, asOf).flatMap {
            case (rate, src, exId, hId) =>
              GlEntryRepo
                .asOfBalances(entity, asOfInstant(asOf))
                .map(_.map {
                  case (key, role, _, netMinor) =>
                    val func = (netMinor / 100).setScale(2, RoundingMode.HALF_UP)
                    ConsLine(
                      entity,
                      key,
                      role,
                      rateClass(role),
                      fc,
                      func,
                      rate,
                      src,
                      exId,
                      hId,
                      (func * rate).setScale(2, RoundingMode.HALF_UP)
                    )
                })
          }
      })
      .flatMap(persist(asOf, presentation, runBy, _))

  private def persist(
      asOf: LocalDate,
      presentation: String,
      runBy: Option[UUID],
      lines: List[ConsLine]
  ): ConnectionIO[Json] = {
    val assets      = lines.filter(_.balancePresentation > 0).map(_.balancePresentation).sum
    val liabilities = lines.filter(_.balancePresentation < 0).map(l => -l.balancePresentation).sum
    val equity      = (assets - liabilities).setScale(2, RoundingMode.HALF_UP)
    val cta         = (-equity).setScale(2, RoundingMode.HALF_UP)
    val fxResidual = lines
      .filter(_.accountKey.startsWith("FX_CLEARING:"))
      .map(_.balancePresentation)
      .sum
      .setScale(2, RoundingMode.HALF_UP)
    // CTA is sound when every entity's native books balance (Σ functional == 0) — nothing lost in translation.
    val nativeSound = lines
      .groupBy(_.entity)
      .values
      .forall(_.map(_.balanceFunctional).sum.setScale(2, RoundingMode.HALF_UP).signum == 0)
    val fxClean = fxResidual.abs < BigDecimal("0.01")
    for {
      runId <- ConsolidationRepo.insertRun(
        asOf,
        presentation,
        assets,
        liabilities,
        equity,
        cta,
        fxResidual,
        nativeSound,
        runBy
      )
      _ <- lines.traverse_(ConsolidationRepo.insertLine(runId, _))
      _ <- ConsolidationRepo.recordControl(
        "CTRL-CTA-BALANCE",
        if (nativeSound) "pass" else "fail",
        Json.obj("run_id" -> runId.toString.asJson, "cta" -> cta.asJson, "native_balanced" -> nativeSound.asJson),
        None
      )
      _ <- ConsolidationRepo.recordControl(
        "CTRL-FXCLEARING-ZERO",
        if (fxClean) "pass" else "fail",
        Json.obj("run_id" -> runId.toString.asJson, "fx_clearing_residual" -> fxResidual.asJson),
        None
      )
    } yield Json.obj(
      "run_id"                -> runId.toString.asJson,
      "as_of"                 -> asOf.toString.asJson,
      "presentation_currency" -> presentation.asJson,
      "total_assets"          -> assets.asJson,
      "total_liabilities"     -> liabilities.asJson,
      "total_equity"          -> equity.asJson,
      "cta"                   -> cta.asJson,
      "fx_clearing_residual"  -> fxResidual.asJson,
      "balanced"              -> nativeSound.asJson,
      "lines" -> lines.map { l =>
        Json.obj(
          "entity_id"            -> l.entity.toString.asJson,
          "account"              -> l.accountKey.asJson,
          "rate_class"           -> l.rateClass.asJson,
          "functional_currency"  -> l.functionalCurrency.asJson,
          "balance_functional"   -> l.balanceFunctional.asJson,
          "rate"                 -> l.rate.asJson,
          "rate_source"          -> l.rateSource.asJson,
          "balance_presentation" -> l.balancePresentation.asJson
        )
      }.asJson
    )
  }

  // Lineage for a recorded run (doc 14 §5.1): the run header + each translated line with its rate provenance, so a
  // consolidated figure drills to the rate/hedge used and the native account it came from (then on to gl_entry/TB).
  def lineage(runId: UUID): F[Option[Json]] =
    (runHead(runId), runLines(runId)).tupled.transact(xa).map {
      case (None, _) => none
      case (Some(head), lines) =>
        head.deepMerge(Json.obj("lines" -> lines.asJson)).some
    }

  private def runHead(runId: UUID): ConnectionIO[Option[Json]] =
    sql"""SELECT as_of, presentation_currency, total_assets, total_liabilities, total_equity, cta, fx_clearing_residual, balanced
          FROM consolidation_run WHERE id = $runId"""
      .query[(LocalDate, String, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal, Boolean)]
      .option
      .map(_.map {
        case (asOf, ccy, a, l, e, cta, fx, bal) =>
          Json.obj(
            "run_id"                -> runId.toString.asJson,
            "as_of"                 -> asOf.toString.asJson,
            "presentation_currency" -> ccy.asJson,
            "total_assets"          -> a.asJson,
            "total_liabilities"     -> l.asJson,
            "total_equity"          -> e.asJson,
            "cta"                   -> cta.asJson,
            "fx_clearing_residual"  -> fx.asJson,
            "balanced"              -> bal.asJson
          )
      })

  private def runLines(runId: UUID): ConnectionIO[List[Json]] =
    sql"""SELECT entity_id, account_key, rate_class, functional_currency, balance_functional, rate, rate_source, balance_presentation
          FROM consolidation_line WHERE run_id = $runId ORDER BY entity_id, account_role, account_key"""
      .query[(UUID, String, String, String, BigDecimal, BigDecimal, String, BigDecimal)]
      .to[List]
      .map(_.map {
        case (e, key, rc, fc, bf, r, rs, bp) =>
          Json.obj(
            "entity_id"            -> e.toString.asJson,
            "account"              -> key.asJson,
            "rate_class"           -> rc.asJson,
            "functional_currency"  -> fc.asJson,
            "balance_functional"   -> bf.asJson,
            "rate"                 -> r.asJson,
            "rate_source"          -> rs.asJson,
            "balance_presentation" -> bp.asJson
          )
      })
}
