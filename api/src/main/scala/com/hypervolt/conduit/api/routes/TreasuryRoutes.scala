package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import com.hypervolt.conduit.treasury.HedgeJson._
import com.hypervolt.conduit.treasury.HedgeProgramRepo
import com.hypervolt.conduit.treasury.HedgeStatus
import doobie._
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.LocalDate
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// Treasury / FX hedging program read surface (M12-Treasury). The program (facility, policy, contracts, coverage)
// and the economic effectiveness stream (hedged vs counterfactual all-spot). Gated on the FX/consolidation view
// permission (treasury layer); figures are projected per the data-layer policy upstream of the desk.
final class TreasuryRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base                        = Secured.base[F](auth)
  private def gate(p: Principal): Boolean = PolicyEngine.hasPermission(p, Action.View, "consolidation")
  private val denied                      = (StatusCode.Forbidden, ApiError("forbidden", "requires view:consolidation"))

  private val program =
    base.get
      .in("api" / "v1" / "treasury" / "program")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else
            HedgeProgramRepo.operatingEntity
              .flatMap {
                case None => Json.obj("entity" -> Json.Null).pure[ConnectionIO]
                case Some(eid) =>
                  (
                    HedgeProgramRepo.facilities(eid),
                    HedgeProgramRepo.policiesAll(eid),
                    HedgeProgramRepo.contracts(eid),
                    HedgeProgramRepo.exposures(eid, LocalDate.of(2025, 1, 1), LocalDate.of(2027, 1, 1))
                  ).mapN { (facs, pols, cons, exps) =>
                    val exposureUsd = exps.map(_.amountUsd).sum
                    val hedgedGbp   = cons.filter(c => HedgeStatus.Open(c.status)).map(_.notionalOpen).sum
                    Json.obj(
                      "facility"  -> facs.headOption.asJson,
                      "policy"    -> pols.asJson,
                      "contracts" -> cons.asJson,
                      "exposure"  -> exps.asJson,
                      "coverage"  -> Json.obj("exposure_usd" -> exposureUsd.asJson, "hedged_notional_gbp" -> hedgedGbp.asJson)
                    )
                  }
              }
              .transact(xa)
              .map(Right(_))
      )

  private val effectiveness =
    base.get
      .in("api" / "v1" / "treasury" / "effectiveness")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          if (!gate(p)) Async[F].pure(Left(denied))
          else
            HedgeProgramRepo.operatingEntity
              .flatMap {
                case None => Json.obj("rows" -> Json.arr()).pure[ConnectionIO]
                case Some(eid) =>
                  HedgeProgramRepo.effectiveness(eid).map { rows =>
                    Json.obj("rows" -> rows.asJson, "total_saving_gbp" -> rows.map(_.savingGbp).sum.asJson)
                  }
              }
              .transact(xa)
              .map(Right(_))
      )

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(List(program, effectiveness))
}
