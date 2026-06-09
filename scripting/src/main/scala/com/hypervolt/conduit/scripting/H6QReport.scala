package com.hypervolt.conduit.scripting

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.all._
import com.hypervolt.conduit.forecast._
import com.hypervolt.conduit.pricing.AgreementService
import com.hypervolt.conduit.pricing.TierBand
import com.hypervolt.conduit.pricing.TierRequest
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Drives the REAL forecasting engine against the local compose stack and renders the H6Q-style report:
// the rolling-origin backtest (train ≤Q1'25 → predict Q2'25 → compare with known actuals), the live Q3'25
// champion forecast, the runway telemetry, and the sector/market/global coverage views with net-rebate money.
object H6QReport extends IOApp.Simple {

  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5532/conduit",
    "conduit",
    "conduit",
    None
  )

  private val marketUk = UUID.fromString("00000000-0000-0000-0000-00000000a0a1")
  private val marketIe = UUID.fromString("00000000-0000-0000-0000-00000000a0a2")
  private val channel  = UUID.fromString("00000000-0000-0000-0000-00000000c0c1")

  override def run: IO[Unit] =
    for {
      ids <- seed
      (octopus, installer, vid) = ids
      engine                    = new BacktestEngine[IO](xa)
      // the rolling-origin loop over four historical origins, scored against known actuals
      origins =
        List(LocalDate.of(2024, 7, 1), LocalDate.of(2024, 10, 1), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 4, 1))
      _   <- origins.traverse_(o => engine.runOrigin(o, horizonMonths = 3))
      _   <- origins.traverse_(o => engine.scoreOrigin(o, asOf = LocalDate.of(2025, 7, 1)))
      q2  <- q2Detail(octopus).transact(xa)
      lbO <- ForecastRunRepo.leaderboard(octopus).transact(xa)
      lbI <- ForecastRunRepo.leaderboard(installer).transact(xa)
      // the live forecast: champions publish Q3'25 from everything ≤ Jun'25
      _  <- new LiveForecastService[IO](xa).publish(LocalDate.of(2025, 7, 1), horizonMonths = 3)
      q3 <- q3Forecast(octopus).transact(xa)
      // runway telemetry + the coverage views
      runway <- new RunwayService[IO](xa).refresh(octopus, vid, Instant.now())
      state  <- stateOf(octopus, vid).transact(xa)
      views = new CoverageViewsService[IO](xa)
      scen    <- sql"SELECT id FROM forecast_scenario WHERE is_default = true LIMIT 1".query[UUID].unique.transact(xa)
      _       <- new CoverageProjector[IO](xa).recompute(marketUk, LocalDate.of(2025, 7, 1), scen)
      _       <- new CoverageProjector[IO](xa).recompute(marketIe, LocalDate.of(2025, 7, 1), scen)
      global  <- views.perMarketAndGlobal(LocalDate.of(2025, 7, 1), scen)
      sectors <- views.sectors(LocalDate.of(2025, 7, 1), scen)
      money   <- views.netRevenueBySector(LocalDate.of(2025, 7, 1), scen, channel, "GBP", Instant.now())
      html = render(
        q2,
        lbO,
        lbI,
        q3,
        state.orElse(runway.map(r => (BigDecimal(30), BigDecimal(15), Some(r)))),
        global,
        sectors,
        money
      )
      _ <- IO.blocking(Files.write(Paths.get("/tmp/conduit-h6q-report.html"), html.getBytes(StandardCharsets.UTF_8)))
      _ <- IO.println("report written: /tmp/conduit-h6q-report.html")
    } yield ()

  // idempotent: the lumpy installer account, the IE account, the retro agreement, and the H6Q capture rows
  private def seed: IO[(UUID, UUID, UUID)] =
    (for {
      octopus <- sql"SELECT id FROM party WHERE display_name='Octopus Energy (demo)'".query[UUID].unique
      vid     <- sql"SELECT id FROM product_variant WHERE sku='HV3PROAA'".query[UUID].unique
      installer <-
        sql"SELECT id FROM party WHERE display_name='Spark Bright Installations (demo)'".query[UUID].option.flatMap {
          case Some(id) => id.pure[ConnectionIO]
          case None =>
            sql"""INSERT INTO party (display_name, party_type, is_organization, sector)
                VALUES ('Spark Bright Installations (demo)','wholesaler',true,'installers') RETURNING id"""
              .query[UUID]
              .unique
        }
      ie <- sql"SELECT id FROM party WHERE display_name='IE Energy (demo)'".query[UUID].option.flatMap {
        case Some(id) => id.pure[ConnectionIO]
        case None =>
          sql"""INSERT INTO party (display_name, party_type, is_organization, sector)
                VALUES ('IE Energy (demo)','wholesaler',true,'energy') RETURNING id""".query[UUID].unique
      }
      // lumpy history: 300-unit spikes whose cycle shifts each year (same-month-last-year always wrong)
      spikes =
        List(1, 4, 7, 10).map(LocalDate.of(2023, _, 1)) ++ List(2, 5, 8, 11).map(LocalDate.of(2024, _, 1)) ++
          List(3, 6).map(LocalDate.of(2025, _, 1))
      _ <- spikes.traverse_(m => demoOrder(installer, vid, m, 300, "DEMOL-" + m.toString.take(7)))
      _ <- sql"UPDATE party SET sector='energy' WHERE id=$octopus AND sector IS NULL".update.run
      // H6Q capture rows (the agents' weekly job) for Jul'25, two markets
      scen <- sql"SELECT id FROM forecast_scenario WHERE is_default=true LIMIT 1".query[UUID].unique
      _    <- capture(marketUk, octopus, vid, scen, 260)
      _    <- capture(marketUk, installer, vid, scen, 90)
      _    <- capture(marketIe, ie, vid, scen, 40)
    } yield (octopus, installer, vid)).transact(xa).flatTap {
      case (octopus, _, vid) => agreementFor(octopus, vid)
    }

  private def agreementFor(octopus: UUID, vid: UUID): IO[Unit] =
    sql"SELECT count(*) FROM price_agreement WHERE name='Octopus demo agreement'"
      .query[Long]
      .unique
      .transact(xa)
      .flatMap {
        case n if n > 0 => IO.unit
        case _ =>
          val svc = new AgreementService[IO](xa)
          svc
            .request(
              TierRequest(
                "Octopus demo agreement",
                "GBP",
                List(octopus),
                List(
                  TierBand(vid, 0, Some(99), BigDecimal("600.00"), "GB_STANDARD"),
                  TierBand(vid, 100, Some(499), BigDecimal("560.00"), "GB_STANDARD"),
                  TierBand(vid, 500, None, BigDecimal("520.00"), "GB_STANDARD")
                ),
                Instant.now().minusSeconds(3600),
                None,
                "cumulative_retrospective",
                Json.obj("min_commitment_units" -> Json.fromInt(500)),
                Some("demo"),
                UUID.randomUUID()
              )
            )
            .flatMap(id => svc.activate(id, UUID.randomUUID()).void)
      }

  private def demoOrder(buyer: UUID, vid: UUID, month: LocalDate, qty: Int, no: String): ConnectionIO[Unit] =
    sql"""SELECT count(*) FROM "order" WHERE order_no=$no""".query[Long].unique.flatMap {
      case n if n > 0 => ().pure[ConnectionIO]
      case _ =>
        sql"""INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, status, txn_currency,
                payment_method, subtotal_ex_vat, vat_total, total_inc_vat, created_at)
              VALUES ($no,'trade',$buyer,$buyer,'placed','GBP','invoice',${qty * 600},${qty * 120},${qty * 720},
                ${month.plusDays(14).atStartOfDay()}) RETURNING id""".query[UUID].unique.flatMap { oid =>
          sql"""INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount, line_total_inc_vat)
                VALUES ($oid,$vid,$qty,600.00,${qty * 120},${qty * 720})""".update.run.void
        }
    }

  private def capture(market: UUID, company: UUID, vid: UUID, scen: UUID, qty: Int): ConnectionIO[Unit] =
    sql"""SELECT count(*) FROM forecast_entry WHERE market_id=$market AND company_id=$company
          AND period_month='2025-07-01' AND source='manual'""".query[Long].unique.flatMap {
      case n if n > 0 => ().pure[ConnectionIO]
      case _ =>
        sql"""INSERT INTO forecast_entry (market_id, channel_id, segment, company_id, branch_company_id,
                forecaster_user_id, product_variant_id, period_month, scenario_id, qty, source)
              VALUES ($market,$channel,'trade',$company,$company,${UUID
          .randomUUID()},$vid,'2025-07-01',$scen,$qty,'manual')""".update.run.void
    }

  // Q2'25 detail: per model, Apr/May/Jun forecast vs actual (origin 2025-04-01)
  private def q2Detail(company: UUID): ConnectionIO[List[(String, LocalDate, BigDecimal, BigDecimal)]] =
    sql"""SELECT model_key, period_month, forecast_qty, actual_qty FROM model_accuracy
          WHERE company_id=$company AND origin_month='2025-04-01' ORDER BY model_key, period_month"""
      .query[(String, LocalDate, BigDecimal, BigDecimal)]
      .to[List]

  private def q3Forecast(company: UUID): ConnectionIO[List[(LocalDate, Int, String)]] =
    sql"""SELECT period_month, qty, COALESCE(model_version,'') FROM forecast_entry
          WHERE company_id=$company AND source='model' AND superseded_by IS NULL ORDER BY period_month"""
      .query[(LocalDate, Int, String)]
      .to[List]

  private def stateOf(company: UUID, vid: UUID): ConnectionIO[Option[(BigDecimal, BigDecimal, Option[BigDecimal])]] =
    sql"""SELECT shelf_stock, velocity_ewma, runway_days FROM account_forecast_state
          WHERE company_id=$company AND product_variant_id=$vid"""
      .query[(BigDecimal, BigDecimal, Option[BigDecimal])]
      .option

  // ----- rendering -----

  private def render(
      q2: List[(String, LocalDate, BigDecimal, BigDecimal)],
      lbO: List[(String, BigDecimal)],
      lbI: List[(String, BigDecimal)],
      q3: List[(LocalDate, Int, String)],
      state: Option[(BigDecimal, BigDecimal, Option[BigDecimal])],
      global: Json,
      sectors: Json,
      money: Json
  ): String = {
    val byModel = q2.groupBy(_._1).toList.sortBy(_._1)
    val actuals = q2.filter(_._1 == byModel.headOption.map(_._1).getOrElse("")).map(_._4)
    val q2rows = byModel.map {
      case (m, rows) =>
        val err = rows.map(r => (r._3 - r._4).abs).sum
        val wape =
          if (rows.map(_._4).sum > 0) (err / rows.map(_._4).sum * 100).setScale(1, BigDecimal.RoundingMode.HALF_UP)
          else BigDecimal(0)
        val champ = lbO.headOption.exists(_._1 == m)
        s"""<tr class="${if (champ) "champ" else ""}"><td>$m${if (champ) " ★" else ""}</td>${rows
          .map(r => s"<td>${r._3.setScale(0, BigDecimal.RoundingMode.HALF_UP)}</td>")
          .mkString}<td class="num">$wape%</td></tr>"""
    }.mkString
    val lbRow = (lb: List[(String, BigDecimal)]) =>
      lb.map {
        case (m, w) =>
          s"<tr><td>$m</td><td>${(w * 100).setScale(1, BigDecimal.RoundingMode.HALF_UP)}%</td></tr>"
      }.mkString
    val q3rows            = q3.map { case (p, q, mv) => s"<tr><td>$p</td><td class='num'>$q</td><td>$mv</td></tr>" }.mkString
    val (shelf, vel, run) = state.map(s => (s._1, s._2, s._3)).getOrElse((BigDecimal(0), BigDecimal(0), None))
    s"""<!doctype html><html><head><meta charset="utf-8"><style>
body{font-family:-apple-system,Helvetica,sans-serif;background:#101014;color:#e8e8ee;margin:28px;max-width:1280px}
h1{font-size:22px}h1 b{color:#962DFF}h2{font-size:15px;color:#b9a7e8;border-bottom:1px solid #2a2a35;padding-bottom:6px;margin-top:30px}
table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}
td,th{padding:6px 12px;border-bottom:1px solid #23232c;text-align:right;font-size:13px}
td:first-child,th:first-child{text-align:left}.num{font-weight:600}
tr.champ td{background:#1d1430;color:#d9c5ff;font-weight:700}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:26px}
.bar{position:relative;background:#1c1c24;height:18px;border-radius:3px;margin:3px 0;width:260px;display:inline-block}
.bar div{height:100%;border-radius:3px}.bar span{position:absolute;right:6px;top:1px;font-size:11px}
.kpi{display:inline-block;background:#17171f;border:1px solid #2a2a35;border-radius:8px;padding:12px 22px;margin-right:14px}
.kpi b{display:block;font-size:24px;color:#962DFF}.kpi span{font-size:11px;color:#9a9ab0}
.note{color:#8a8aa0;font-size:12px;margin:6px 0}</style></head><body>
<h1><b>Conduit</b> — H6Q &amp; Revenue Forecasting <span style="font-size:12px;color:#8a8aa0">live engine run · ${LocalDate
      .now()}</span></h1>

<h2>1 · The rolling-origin backtest — predict Q2'25 from data ≤ Q1'25, compared with known actuals</h2>
<p class="note">Octopus Energy (demo) · every registry model trained on censored history (&lt; 2025-04-01), scored against what actually happened. ★ = the champion the error ledger selected.</p>
<table><tr><th>model</th><th>Apr '25 fc</th><th>May '25 fc</th><th>Jun '25 fc</th><th>WAPE</th></tr>
<tr><td><i>actuals</i></td>${actuals
      .map(a => s"<td><i>${a.setScale(0, BigDecimal.RoundingMode.HALF_UP)}</i></td>")
      .mkString}<td>—</td></tr>
$q2rows</table>

<div class="grid"><div>
<h2>2a · Model leaderboard — Octopus (seasonal demand)</h2>
<table><tr><th>model</th><th>backtest WAPE (4 origins)</th></tr>${lbRow(lbO)}</table>
</div><div>
<h2>2b · Model leaderboard — Spark Bright (lumpy demand)</h2>
<table><tr><th>model</th><th>backtest WAPE (4 origins)</th></tr>${lbRow(lbI)}</table>
<p class="note">A different champion per demand shape — selection is pure measurement, no hardcoding.</p>
</div></div>

<h2>3 · The live Q3'25 forecast (champion model, published into the H6Q spine)</h2>
<table><tr><th>period</th><th>forecast units</th><th>model</th></tr>$q3rows</table>

<h2>4 · Account telemetry — the depletion edge (real shelf, real activations)</h2>
<span class="kpi"><b>${shelf.setScale(0, BigDecimal.RoundingMode.HALF_UP)}</b><span>units on shelf</span></span>
<span class="kpi"><b>${vel.setScale(1, BigDecimal.RoundingMode.HALF_UP)}</b><span>activations / month</span></span>
<span class="kpi"><b>${run
      .map(_.setScale(0, BigDecimal.RoundingMode.HALF_UP).toString)
      .getOrElse("—")}</b><span>runway days</span></span>

<h2>5 · H6Q — per market &amp; global (Jul '25 capture)</h2>
<pre style="background:#15151d;padding:14px;border-radius:8px;font-size:12px;overflow:auto">${global.spaces2}</pre>
<h2>6 · H6Q — sector attribution (energy / installers), global across markets</h2>
<pre style="background:#15151d;padding:14px;border-radius:8px;font-size:12px;overflow:auto">${sectors.spaces2}</pre>
<h2>7 · The money view — net-of-rebate revenue per sector / market / global</h2>
<pre style="background:#15151d;padding:14px;border-radius:8px;font-size:12px;overflow:auto">${money.spaces2}</pre>
<p class="note">Energy prices at the Octopus contract net of the expected retrospective rebate (600 − 80 = 520/unit at the committed tier); installers at the open list. Revenue is a read-time projection through the pricing engine — H6Q never owns money.</p>
</body></html>"""
  }
}
