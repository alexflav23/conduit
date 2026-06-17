package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.access._
import com.hypervolt.conduit.api.ApiMetrics
import com.hypervolt.conduit.api.auth.ApiError
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.auth.Secured
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// Warranty provision register (M8, doc 14): the forward warranty liability. Each in-warranty unit carries an
// expected-claim accrual = the measured replacement rate (17.99%, from the RMA/free-shipment analysis) × the
// unit's landed cost (or the £455 fleet-average replacement cost where the lot cost is unknown). The register
// totals the liability and lists the in-warranty units; released/consumed track against real RMA claims.
final class WarrantyRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {

  private val base = Secured.base[F](auth)
  private val Repl = BigDecimal("0.1799") // measured warranty replacement rate
  private val Avg  = BigDecimal("455")    // fleet-average replacement cost (free-shipment analysis)

  private def err(s: StatusCode, c: String, m: String): (StatusCode, ApiError) = (s, ApiError(c, m))

  private val provisions =
    base.get
      .in("api" / "v1" / "warranty" / "provisions")
      .out(jsonBody[Json])
      .serverLogic(p =>
        _ =>
          if (!PolicyEngine.hasPermission(p, Action.View, "order"))
            Async[F].pure(Left(err(StatusCode.Forbidden, "forbidden", "requires view:order")))
          else
            sql"""WITH prov AS (
                    SELECT s.serial_no, COALESCE(regexp_replace(party.display_name,'^MRP:\s*',''), '—') AS owner,
                           round(COALESCE(b.landed_unit_cost, $Avg) * $Repl, 2) AS provision
                    FROM serial_unit s
                    LEFT JOIN lot_batch b ON b.id = s.lot_batch_id
                    LEFT JOIN dispatch d ON d.id = s.dispatch_id
                    LEFT JOIN "order" o ON o.id = d.order_id
                    LEFT JOIN party ON party.id = o.sold_to_party_id
                    WHERE s.warranty_end >= current_date)
                  SELECT jsonb_build_object(
                    'hasCost', true,
                    'totals', jsonb_build_object(
                      'active', (SELECT count(*) FROM prov),
                      'provision', COALESCE((SELECT sum(provision) FROM prov), 0),
                      'outstanding', COALESCE((SELECT sum(provision) FROM prov), 0)),
                    'rows', COALESCE((SELECT jsonb_agg(jsonb_build_object(
                              'sn', serial_no, 'serial', serial_no, 'owner', owner,
                              'provision', provision, 'outstanding', provision, 'pct', 0) ORDER BY provision DESC)
                            FROM (SELECT * FROM prov ORDER BY provision DESC LIMIT 200) t), '[]'::jsonb))"""
              .query[Json]
              .unique
              .transact(xa)
              .map(Right(_))
      )

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F](ApiMetrics.serverOptions[F]).toRoutes(List(provisions))
}
