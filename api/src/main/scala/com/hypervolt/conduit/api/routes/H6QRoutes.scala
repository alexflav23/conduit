package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.forecast.DemandBoardRepo
import com.hypervolt.conduit.forecast.ForecastLine
import com.hypervolt.conduit.forecast.ForecastQueryRepo
import com.hypervolt.conduit.forecast.ForecastService
import com.hypervolt.conduit.notification.NotificationRepo
import com.hypervolt.conduit.revenue.RevenueQueryRepo
import com.hypervolt.conduit.supply.AutoPoProposer
import com.hypervolt.conduit.supply.SerialShelfRepo
import com.hypervolt.conduit.supply.SupplyCommitmentService
import com.hypervolt.conduit.supply.SupplyQueryRepo
import com.hypervolt.conduit.supply.WaterfallRepo
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Codec
import io.circe.Json
import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._
import java.time.LocalDate
import java.util.UUID
import org.http4s.HttpRoutes
import scala.util.Try
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import com.hypervolt.conduit.api.ApiMetrics
import sttp.tapir.server.http4s.Http4sServerInterpreter

final case class SubmitLineReq(variant: String, period: String, scenario: String, qty: Int)
object SubmitLineReq { implicit val codec: Codec[SubmitLineReq] = deriveCodec }

final case class SubmitForecastReq(cycle: String, lines: List[SubmitLineReq])
object SubmitForecastReq { implicit val codec: Codec[SubmitForecastReq] = deriveCodec }

final case class SkipReq(cycle: String, reason: String)
object SkipReq { implicit val codec: Codec[SkipReq] = deriveCodec }

final case class SubmitMixReq(cycle: String, period: String, scenario: String, qty: Int)
object SubmitMixReq { implicit val codec: Codec[SubmitMixReq] = deriveCodec }

final case class AutoPoReq(supplier: String, market: String, period: String, scenario: String, asOf: String)
object AutoPoReq { implicit val codec: Codec[AutoPoReq] = deriveCodec }

final case class ApprovePoReq(supplier: String, variant: String, target: String)
object ApprovePoReq { implicit val codec: Codec[ApprovePoReq] = deriveCodec }

// H6Q REST surface (doc 12 §11). Capture is own-scope create:forecast; the coverage board is
// view:pipeline_coverage, scope-filtered and layer-projected (volume/commercial/profitability).
final class H6QRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base    = Secured.base[F](auth)
  private val service = new ForecastService[F](xa)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))
  private def uuid(s: String): Either[(StatusCode, ApiError), UUID] =
    Try(UUID.fromString(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid id: $s"))
  private def date(s: String): Either[(StatusCode, ApiError), LocalDate] =
    Try(LocalDate.parse(s)).toEither.leftMap(_ => err(StatusCode.BadRequest, "bad_request", s"invalid date: $s"))

  private val scenarios =
    base.get
      .in("api" / "v1" / "h6q" / "scenarios")
      .out(jsonBody[Json])
      .serverLogic(_ => _ => ForecastQueryRepo.scenarios.transact(xa).map(rows => Right(Json.fromValues(rows))))

  private val variants =
    base.get
      .in("api" / "v1" / "h6q" / "variants")
      .out(jsonBody[Json])
      .serverLogic(_ => _ => ForecastQueryRepo.variants.transact(xa).map(rows => Right(Json.fromValues(rows))))

  private val cycles =
    base.get
      .in("api" / "v1" / "h6q" / "cycles")
      .in(query[Option[String]]("status"))
      .out(jsonBody[Json])
      .serverLogic(_ =>
        status => ForecastQueryRepo.cycles(status).transact(xa).map(rows => Right(Json.fromValues(rows)))
      )

  // The owner's capture grid for the current open cycle (own scope — keyed by the principal's user id).
  private val myForecasts =
    base.get
      .in("api" / "v1" / "h6q" / "my-forecasts")
      .out(jsonBody[Json])
      .serverLogic(principal =>
        _ =>
          service.currentOpenCycle().flatMap {
            case None => Async[F].pure(Right(Json.obj("cycle" -> Json.Null, "accounts" -> Json.arr())))
            case Some(cycleId) =>
              ForecastQueryRepo
                .myForecasts(principal.userId, cycleId)
                .transact(xa)
                .map(rows => Right(Json.obj("cycle" -> cycleId.toString.asJson, "accounts" -> Json.fromValues(rows))))
          }
      )

  private val submit =
    base.post
      .in("api" / "v1" / "h6q" / "my-forecasts" / path[String]("company_id") / "submit")
      .in(jsonBody[SubmitForecastReq])
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (companyStr, req) =>
          val parsed = for {
            account <- uuid(companyStr)
            cycle   <- uuid(req.cycle)
            lines <- req.lines.traverse(l =>
              (uuid(l.variant), date(normaliseMonth(l.period)), uuid(l.scenario))
                .mapN((v, m, sc) => ForecastLine(v, m, sc, l.qty))
            )
          } yield (account, cycle, lines)
          parsed match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((account, cycle, lines)) =>
              service.submit(principal.userId, account, cycle, lines, Some("desk")).map {
                case Left("not_owner") =>
                  Left(err(StatusCode.Forbidden, "forbidden", "you do not own this account this cycle"))
                case Left("cycle_closed") => Left(err(StatusCode.Conflict, "cycle_closed", "the cycle is closed"))
                case Left(other)          => Left(err(StatusCode.UnprocessableEntity, "invalid", other))
                case Right(changed) =>
                  Right(
                    Json.obj(
                      "company_id" -> account.toString.asJson,
                      "versioned"  -> changed.asJson,
                      "status"     -> "submitted".asJson
                    )
                  )
              }
          }
      })

  // Capture an aggregate unit count; the SKU mix splits it into a per-SKU forecast (doc 12 §1.2).
  private val submitMix =
    base.post
      .in("api" / "v1" / "h6q" / "my-forecasts" / path[String]("company_id") / "submit-mix")
      .in(jsonBody[SubmitMixReq])
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (companyStr, req) =>
          (uuid(companyStr), uuid(req.cycle), date(normaliseMonth(req.period)), uuid(req.scenario)).tupled match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((account, cycle, period, scenario)) =>
              service.submitMix(principal.userId, account, cycle, period, scenario, req.qty, Some("desk")).map {
                case Left("not_owner") =>
                  Left(err(StatusCode.Forbidden, "forbidden", "you do not own this account this cycle"))
                case Left("cycle_closed") => Left(err(StatusCode.Conflict, "cycle_closed", "the cycle is closed"))
                case Left("no_sku_mix") =>
                  Left(
                    err(
                      StatusCode.UnprocessableEntity,
                      "no_sku_mix",
                      "no SKU mix configured for this account's channel/market"
                    )
                  )
                case Left(other) => Left(err(StatusCode.UnprocessableEntity, "invalid", other))
                case Right(n) =>
                  Right(
                    Json.obj(
                      "company_id" -> account.toString.asJson,
                      "sku_lines"  -> n.asJson,
                      "status"     -> "submitted".asJson
                    )
                  )
              }
          }
      })

  private val skip =
    base.post
      .in("api" / "v1" / "h6q" / "my-forecasts" / path[String]("company_id") / "skip")
      .in(jsonBody[SkipReq])
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (companyStr, req) =>
          (uuid(companyStr), uuid(req.cycle)).tupled match {
            case Left(e) => Async[F].pure(Left(e))
            case Right((account, cycle)) =>
              service.skip(principal.userId, account, cycle, req.reason).map {
                case Left(_)  => Left(err(StatusCode.Forbidden, "forbidden", "you do not own this account this cycle"))
                case Right(_) => Right(Json.obj("company_id" -> account.toString.asJson, "status" -> "skipped".asJson))
              }
          }
      })

  private val outstanding =
    base.get
      .in("api" / "v1" / "h6q" / "outstanding")
      .in(query[String]("cycle"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        cycleStr =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            uuid(cycleStr) match {
              case Left(e) => Async[F].pure(Left(e))
              case Right(cycleId) =>
                ForecastQueryRepo.outstanding(cycleId).transact(xa).map(rows => Right(Json.fromValues(rows)))
            }
      )

  private val accuracy =
    base.get
      .in("api" / "v1" / "h6q" / "accuracy")
      .in(query[String]("company"))
      .in(query[String]("period"))
      .in(query[Option[String]]("basis"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (companyStr, periodStr, basisOpt) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            (uuid(companyStr), date(normaliseMonth(periodStr))).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((company, period)) =>
                ForecastQueryRepo
                  .accuracy(company, period, basisOpt.getOrElse("sell_in"))
                  .transact(xa)
                  .map(rows => Right(Json.fromValues(rows)))
            }
      })

  // Forward-visibility notifications (doc 12 §2.6): who was told H6Q shifted, on what channel, with what status.
  private val notifications =
    base.get
      .in("api" / "v1" / "h6q" / "notifications")
      .out(jsonBody[Json])
      .serverLogic(principal =>
        _ =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else NotificationRepo.recent(50).transact(xa).map(rows => Right(Json.fromValues(rows)))
      )

  // The coverage board at one level (org axis or by agent), scope-filtered and layer-projected.
  private val coverage =
    base.get
      .in("api" / "v1" / "h6q" / "coverage")
      .in(query[String]("market"))
      .in(query[String]("period"))
      .in(query[String]("scenario"))
      .in(query[Option[String]]("group_by"))
      .in(query[Option[String]]("variant"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (marketStr, periodStr, scenarioStr, groupByOpt, variantOpt) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            (
              uuid(marketStr),
              date(normaliseMonth(periodStr)),
              uuid(scenarioStr),
              variantOpt.traverse(uuid)
            ).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((market, period, scenario, variant)) =>
                val level = groupByOpt.getOrElse("market")
                ForecastQueryRepo
                  .coverage(market, period, scenario, level, variant)
                  .transact(xa)
                  .map(rows =>
                    Right(Json.fromValues(rows.map(r => Projection.projectFor(principal, "pipeline_coverage", r))))
                  )
            }
      })

  private val supplyCommitments = new SupplyCommitmentService[F](xa)
  private val autoPo            = new AutoPoProposer[F](xa, supplyCommitments)

  // Auto-PO proposer: diff H6Q demand vs the firm commitment + available stock, propose deltas within the
  // time-fence headroom (the rest is blocked + raises a divergence warning).
  private val autoPoPropose =
    base.post
      .in("api" / "v1" / "h6q" / "auto-po")
      .in(jsonBody[AutoPoReq])
      .out(jsonBody[Json])
      .serverLogic(principal =>
        req =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            (
              uuid(req.supplier),
              uuid(req.market),
              date(normaliseMonth(req.period)),
              uuid(req.scenario),
              date(req.asOf)
            ).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((supplier, market, period, scenario, asOf)) =>
                autoPo.propose(supplier, market, period, scenario, asOf).map { props =>
                  Right(
                    Json.fromValues(
                      props.map(pp =>
                        Json.obj(
                          "product_variant_id" -> pp.variant.toString.asJson,
                          "demand"             -> pp.demand.asJson,
                          "committed"          -> pp.committed.asJson,
                          "available"          -> pp.available.asJson,
                          "net_need"           -> pp.netNeed.asJson,
                          "proposed_delta"     -> pp.proposedDelta.asJson,
                          "blocked_qty"        -> pp.blocked.asJson,
                          "zone"               -> pp.zone.asJson
                        )
                      )
                    )
                  )
                }
            }
      )

  // The immutable-log view: recognised revenue + the TigerBeetle transfer ids that prove it (doc 04 §Ledger).
  private val ledger =
    base.get
      .in("api" / "v1" / "h6q" / "ledger")
      .in(query[String]("market"))
      .in(query[String]("period"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (marketStr, periodStr) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            (uuid(marketStr), date(normaliseMonth(periodStr))).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((market, period)) =>
                (RevenueQueryRepo.totals(market, period), RevenueQueryRepo.recognitions(market, period)).tupled
                  .transact(xa)
                  .map { case (tot, rows) => Right(Json.obj("totals" -> tot, "recognitions" -> Json.fromValues(rows))) }
            }
      })

  // Supply window reads: contract manufacturers, the firm-commitment horizon, proposals, divergence warnings.
  private def supplyRead(path: String, q: UUID => doobie.ConnectionIO[List[Json]]) =
    base.get
      .in("api" / "v1" / "h6q" / "supply" / path)
      .in(query[String]("supplier"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        supStr =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            uuid(supStr) match {
              case Left(e)    => Async[F].pure(Left(e))
              case Right(sup) => q(sup).transact(xa).map(rows => Right(Json.fromValues(rows)))
            }
      )

  private val suppliers =
    base.get
      .in("api" / "v1" / "h6q" / "suppliers")
      .out(jsonBody[Json])
      .serverLogic(_ =>
        _ => SupplyQueryRepo.contractManufacturers.transact(xa).map(rows => Right(Json.fromValues(rows)))
      )

  private val supplyCommitmentsR = supplyRead("commitments", SupplyQueryRepo.commitments)
  private val supplyProposalsR   = supplyRead("proposals", SupplyQueryRepo.proposals)
  private val supplyWarningsR    = supplyRead("warnings", SupplyQueryRepo.warnings)

  // Approve a proposal: commit its (committed + proposed_delta) to the firm PO (within headroom by construction).
  private val supplyApprove =
    base.post
      .in("api" / "v1" / "h6q" / "supply" / "approve")
      .in(jsonBody[ApprovePoReq])
      .out(jsonBody[Json])
      .serverLogic(principal =>
        req =>
          if (
            !PolicyEngine.hasPermission(principal, Action.Edit, "pipeline_coverage") &&
            !PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage")
          )
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires pipeline_coverage")))
          else
            (uuid(req.supplier), uuid(req.variant), date(req.target)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((sup, v, target)) =>
                SupplyQueryRepo.proposal(sup, v, target).transact(xa).flatMap {
                  case None => Async[F].pure(Left(err(StatusCode.NotFound, "not_found", "no open proposal")))
                  case Some(qty) =>
                    supplyCommitments
                      .commit(sup, v, target, qty, LocalDate.now(java.time.ZoneOffset.UTC), force = false)
                      .flatMap {
                        case Left(reason) =>
                          Async[F].pure(Left(err(StatusCode.Conflict, reason, s"cannot commit: $reason")))
                        case Right(a) =>
                          SupplyQueryRepo
                            .markCommitted(sup, v, target)
                            .transact(xa)
                            .as(Right(Json.obj("committed_qty" -> qty.asJson, "zone" -> a.zone.asJson)))
                      }
                }
            }
      )

  // Real-time per-account shelf (shipped/activated/on-shelf), attributed by Conduit at dispatch.
  private val shelf =
    base.get
      .in("api" / "v1" / "h6q" / "shelf")
      .in(query[Option[String]]("company"))
      .out(jsonBody[Json])
      .serverLogic(principal =>
        companyOpt =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            companyOpt match {
              case None => SerialShelfRepo.board(100).transact(xa).map(rows => Right(Json.fromValues(rows)))
              case Some(compStr) =>
                uuid(compStr) match {
                  case Left(e)  => Async[F].pure(Left(e))
                  case Right(c) => SerialShelfRepo.shelf(c).transact(xa).map(Right(_))
                }
            }
      )

  // The demand→revenue waterfall for a SKU/month: forecast → CM commitment → produced → delivered → ordered →
  // shipped → revenue, each stage distinct, the shipped→revenue tail provable in the ledger (doc 04 §Ledger).
  private val waterfall =
    base.get
      .in("api" / "v1" / "h6q" / "waterfall")
      .in(query[String]("variant"))
      .in(query[String]("period"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (variantStr, periodStr) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            (uuid(variantStr), date(normaliseMonth(periodStr))).tupled match {
              case Left(e)                  => Async[F].pure(Left(e))
              case Right((variant, period)) => WaterfallRepo.waterfall(variant, period).transact(xa).map(Right(_))
            }
      })

  // The per-SKU breakdown of a level (the Quarterly-Forecast-Dashboard view: total split by SKU).
  private val coverageBySku =
    base.get
      .in("api" / "v1" / "h6q" / "coverage" / "by-sku")
      .in(query[String]("market"))
      .in(query[String]("period"))
      .in(query[String]("scenario"))
      .in(query[Option[String]]("group_by"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (marketStr, periodStr, scenarioStr, groupByOpt) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            (uuid(marketStr), date(normaliseMonth(periodStr)), uuid(scenarioStr)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((market, period, scenario)) =>
                ForecastQueryRepo
                  .coverageBySku(market, period, scenario, groupByOpt.getOrElse("market"))
                  .transact(xa)
                  .map(rows =>
                    Right(Json.fromValues(rows.map(r => Projection.projectFor(principal, "pipeline_coverage", r))))
                  )
            }
      })

  // The demand board (spec/ui H6Q): forecast by revenue segment with quarters, trend, shipped, attainment and
  // tier-aware revenue, each segment expandable to its contributing accounts.
  private val demandBoard =
    base.get
      .in("api" / "v1" / "h6q" / "demand-board")
      .in(query[String]("market"))
      .in(query[String]("scenario"))
      .in(query[Option[String]]("currency"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (marketStr, scenarioStr, currencyOpt) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            (uuid(marketStr), uuid(scenarioStr)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((market, scenario)) =>
                val currency = currencyOpt.map(_.trim.toUpperCase).filter(_.nonEmpty).getOrElse("GBP")
                DemandBoardRepo.board(market, scenario, contributorsPerSegment = 12, currency = currency)
                  .transact(xa)
                  .map(j => Right(Projection.projectFor(principal, "pipeline_coverage", j)))
            }
      })

  private val coverageMatrix =
    base.get
      .in("api" / "v1" / "h6q" / "coverage" / "matrix")
      .in(query[String]("market"))
      .in(query[String]("scenario"))
      .in(query[Option[String]]("group_by"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (marketStr, scenarioStr, groupByOpt) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            (uuid(marketStr), uuid(scenarioStr)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((market, scenario)) =>
                val io = groupByOpt.map(_.trim.toLowerCase).filter(g => Set("account", "sector", "market").contains(g)) match {
                  case Some(g) => ForecastQueryRepo.coverageMatrixBy(market, scenario, g, limit = 40)
                  case None    => ForecastQueryRepo.coverageMatrix(market, scenario)
                }
                io.transact(xa)
                  .map(rows => Right(Json.fromValues(rows.map(r => Projection.projectFor(principal, "pipeline_coverage", r)))))
            }
      })

  private val reconcile =
    base.get
      .in("api" / "v1" / "h6q" / "coverage" / "reconcile")
      .in(query[String]("market"))
      .in(query[String]("period"))
      .in(query[String]("scenario"))
      .out(jsonBody[Json])
      .serverLogic(principal => {
        case (marketStr, periodStr, scenarioStr) =>
          if (!PolicyEngine.hasPermission(principal, Action.View, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:pipeline_coverage")))
          else
            (uuid(marketStr), date(normaliseMonth(periodStr)), uuid(scenarioStr)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((market, period, scenario)) =>
                ForecastQueryRepo.reconcile(market, period, scenario).transact(xa).map(Right(_))
            }
      })

  // Output-only export (doc 12 §8.4): a layer-respecting CSV of the board. Volume-only principals get no money
  // (the rows are layer-projected first); requires the export action and is audited. Nothing is ever imported.
  private val exportCsv =
    base.get
      .in("api" / "v1" / "h6q" / "export")
      .in(query[String]("market"))
      .in(query[String]("period"))
      .in(query[String]("scenario"))
      .in(query[Option[String]]("group_by"))
      .out(stringBody)
      .serverLogic(principal => {
        case (marketStr, periodStr, scenarioStr, groupByOpt) =>
          if (!PolicyEngine.hasPermission(principal, Action.Export, "pipeline_coverage"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires export:pipeline_coverage")))
          else
            (uuid(marketStr), date(normaliseMonth(periodStr)), uuid(scenarioStr)).tupled match {
              case Left(e) => Async[F].pure(Left(e))
              case Right((market, period, scenario)) =>
                val level = groupByOpt.getOrElse("branch")
                ForecastQueryRepo
                  .coverage(market, period, scenario, level, None)
                  .flatMap { rows =>
                    val projected = rows.map(r => Projection.projectFor(principal, "pipeline_coverage", r))
                    sql"INSERT INTO audit_log (entity_type, entity_id, action, actor_user_id) VALUES ('pipeline_coverage', $market, 'export', ${principal.userId})".update.run
                      .as(toCsv(projected))
                  }
                  .transact(xa)
                  .map(Right(_))
            }
      })

  private def toCsv(rows: List[Json]): String = {
    val cols = List(
      "level",
      "branch_company_id",
      "agent_user_id",
      "forecast_qty",
      "shipped_qty",
      "activated_qty",
      "coverage_pct",
      "coverage_ex_account_pct",
      "wow_delta",
      "forecast_revenue",
      "forecast_margin"
    )
    // layer-projected: a column the principal's layer can't see is absent from every row, so it drops from the export.
    val present = cols.filter(c => rows.exists(r => r.hcursor.downField(c).focus.exists(!_.isNull)))
    def cell(r: Json, c: String): String =
      r.hcursor.downField(c).focus match {
        case Some(j) if !j.isNull => j.asString.getOrElse(j.noSpaces)
        case _                    => ""
      }
    (present.mkString(",") :: rows.map(r => present.map(cell(r, _)).mkString(","))).mkString("\n")
  }

  private def normaliseMonth(s: String): String = if (s.length == 7) s + "-01" else s

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(
      List(
        scenarios,
        variants,
        cycles,
        myForecasts,
        submit,
        submitMix,
        skip,
        outstanding,
        accuracy,
        notifications,
        coverage,
        coverageBySku,
        coverageMatrix,
        demandBoard,
        waterfall,
        autoPoPropose,
        suppliers,
        supplyCommitmentsR,
        supplyProposalsR,
        supplyWarningsR,
        supplyApprove,
        shelf,
        ledger,
        reconcile,
        exportCsv
      )
    )
}
