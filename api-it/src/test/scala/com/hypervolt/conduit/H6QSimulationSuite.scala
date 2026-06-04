package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.forecast._
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.hypervolt.conduit.supply._
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// An end-to-end H6Q SIMULATION rendered as a human-readable HTML report (written to h6q-simulation.html) so the
// whole mechanism can be eyeballed and validated — while every invariant is asserted as it runs. It walks the
// full chain: multi-channel/agent/SKU capture (incl. SKU-mix expansion, append-only revision, skip) → Hyperview
// precedence → bottom-up coverage (branch ≡ agent) → firm-commitment time fences + divergence warnings →
// production shortfall carry → parts buffer → order → dispatch → ASC-606 revenue in the ledger → accuracy →
// the demand→revenue waterfall.
object H6QSimulationSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val cadence = "sim"
  private val asOf    = LocalDate.of(2026, 6, 1)
  private val month   = LocalDate.of(2026, 7, 1)

  // ---------- tiny HTML helpers ----------
  private val sb                     = new StringBuilder
  private def h(title: String): Unit = sb.append(s"<h2>$title</h2>\n")
  private def p(text: String): Unit  = sb.append(s"<p>$text</p>\n")
  private def table(headers: Seq[String], rows: Seq[Seq[String]]): Unit = {
    sb.append("<table><thead><tr>")
    headers.foreach(x => sb.append(s"<th>$x</th>"))
    sb.append("</tr></thead><tbody>")
    rows.foreach { r => sb.append("<tr>"); r.foreach(c => sb.append(s"<td>$c</td>")); sb.append("</tr>") }
    sb.append("</tbody></table>\n")
  }

  test(
    "H6Q end-to-end simulation: capture → coverage → commitment → production → revenue → waterfall (renders a report)"
  ) {
    case (xa, client) =>
      val fc     = new ForecastService[IO](xa)
      val proj   = new CoverageProjector[IO](xa)
      val hv     = new HyperviewService[IO](xa)
      val sc     = new SupplyCommitmentService[IO](xa)
      val prod   = new ProductionService[IO](xa)
      val buf    = new ComponentBufferService[IO](xa)
      val disp   = new DispatchService[IO](xa)
      val acc    = new AccuracyScorer[IO](xa)
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val rev    = new RevenueRecognitionService[IO](xa, ledger)

      val market   = UUID.randomUUID()
      val chDist   = UUID.randomUUID()
      val chRetail = UUID.randomUUID()

      def user(name: String): IO[UUID] =
        sql"INSERT INTO app_user (keycloak_id, name) VALUES (${s"sim-${UUID.randomUUID()}"}, $name) RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      def sku(code: String, serialised: Boolean): IO[UUID] =
        (for {
          fam <-
            sql"INSERT INTO product_family (code, name) VALUES (${s"HOME3-${UUID.randomUUID().toString.take(6)}"},'Home 3 Pro') RETURNING id"
              .query[UUID]
              .unique
          v <-
            sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, $code, 'v3', $serialised) RETURNING id"
              .query[UUID]
              .unique
        } yield v).transact(xa)
      def party(
          name: String,
          pType: String,
          channel: UUID,
          segment: String,
          owner: Option[UUID],
          parent: Option[UUID]
      ): IO[UUID] =
        sql"""INSERT INTO party (display_name, party_type, is_organization, roles, channel_id, market_id, segment, account_manager_user_id, parent_party_id, status)
              VALUES ($name, $pType, true, '{forecastable}', $channel, $market, $segment, $owner, $parent, 'active') RETURNING id"""
          .query[UUID]
          .unique
          .transact(xa)
      def scenarioP50: IO[UUID] =
        sql"SELECT id FROM forecast_scenario WHERE type='P50' AND toggle_basis IS NULL".query[UUID].unique.transact(xa)
      def skuName(id: UUID): IO[String] =
        sql"SELECT sku FROM product_variant WHERE id=$id".query[String].unique.transact(xa)

      for {
        // ---- cast: agents, channels, SKUs, accounts, mix ----
        _ <-
          sql"UPDATE forecast_cycle SET status='closed' WHERE cadence=$cadence AND status='open'".update.run
            .transact(xa)
        asha  <- user("Asha (Distributor)")
        ben   <- user("Ben (Distributor)")
        cara  <- user("Cara (Retail)")
        p50   <- scenarioP50
        black <- sku(s"HV3PRO-BLK-075-${UUID.randomUUID().toString.take(4)}", serialised = true) // 7.5m black
        white <-
          sku(
            s"HV3PRO-WHT-050-${UUID.randomUUID().toString.take(4)}",
            serialised = false
          ) // 5m white (non-serialised for the revenue leg)
        grey  <- sku(s"HV3PRO-GRY-050-${UUID.randomUUID().toString.take(4)}", serialised = true) // 5m grey
        cef   <- party("CEF (master)", "wholesaler", chDist, "wholesale", None, None)
        leeds <- party("CEF Leeds", "branch", chDist, "wholesale", Some(asha), Some(cef))
        york  <- party("CEF York", "branch", chDist, "wholesale", Some(ben), Some(cef))
        web   <- party("Web Retail", "installer", chRetail, "retail", Some(cara), None)
        _ <-
          SkuMixRepo
            .createMix(
              "UK distributor mix",
              Some(chDist),
              Some(market),
              List(black -> BigDecimal("0.50"), white -> BigDecimal("0.30"), grey -> BigDecimal("0.20"))
            )
            .transact(xa)

        // ---- weekly cycle opens; who-owes generated ----
        cyc <- fc.openCycle(asOf, cadence).map(_._1)

        // ---- capture: per-SKU, via-mix, retail; an append-only revision; a skip ----
        _ <- fc.submit(
          asha,
          leeds,
          cyc,
          List(ForecastLine(black, month, p50, 120), ForecastLine(white, month, p50, 60)),
          Some("ipad")
        )
        _ <-
          fc.submit(
            asha,
            leeds,
            cyc,
            List(ForecastLine(black, month, p50, 140)),
            Some("ipad")
          ) // revise 120 -> 140 (append-only)
        _ <- fc.submitMix(ben, york, cyc, month, p50, 200, Some("desk")) // 200 split 100/60/40 by the mix
        _ <- fc.submit(cara, web, cyc, List(ForecastLine(white, month, p50, 40)), Some("web"))
        // Hyperview publishes a retail grey line; no manual estimate there -> Hyperview is the line
        _ <- hv.publish(web, grey, month, p50, 30, "prophet-v3")

        // ---- bottom-up rollup ----
        _ <- proj.recompute(market, month, p50)

        // ---- contract-manufacturer firm-commitment window (Volex) ----
        volex <-
          sql"INSERT INTO supplier (name, billing_currency) VALUES ('Volex','USD') RETURNING id"
            .query[UUID]
            .unique
            .transact(xa)
        freeWk = asOf.plusDays(210); flexWk = asOf.plusDays(90); frozenWk = asOf.plusDays(14)
        cFree <- sc.commit(volex, black, freeWk, 300, asOf, force = false) // establish in free
        _     <- sc.commit(volex, black, flexWk, 100, asOf, force = false) // establish in flex
        cOver <- sc.commit(volex, black, flexWk, 130, asOf, force = false) // +30% rejected (>20%)
        cOk   <- sc.commit(volex, black, flexWk, 115, asOf, force = false) // +15% admitted
        _ <-
          sql"INSERT INTO supply_commitment (supplier_id, product_variant_id, target_date, qty, zone) VALUES ($volex,$black,$frozenWk,100,'frozen')".update.run
            .transact(xa)
        warn <- sc.checkDemand(volex, black, frozenWk, asOf, 150, "sales_input") // diverges from frozen PO -> warning

        // ---- production reality: shortfall carries to next window ----
        _      <- sc.commit(volex, white, month, 80, asOf, force = true) // a July firm PO for white
        report <- prod.report(volex, white, month, produced = 60)        // built only 60 -> 20 carries

        // ---- parts buffer (sized to P50; convert some to FG) ----
        _     <- buf.setTarget(Some(volex), Some(black), 500, "p50")
        _     <- buf.setBuffer(volex, black, 320)
        bufSt <- buf.status(volex, black)
        _     <- buf.convertToFinishedGoods(volex, black, 120)

        // ---- sell-side actuals: order -> dispatch -> deliver -> ASC-606 revenue (white) ----
        _ <-
          sql"INSERT INTO lot_batch (batch_no, product_variant_id, qty, unit_cost_usd, fx_rate, fx_basis, landed_unit_cost, currency, received_date) VALUES (${s"B-${UUID
            .randomUUID()}"},$white,200,120,1.0,'spot',120,'GBP',$month)".update.run.transact(xa)
        ord <-
          sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, market_id, channel_id, status, txn_currency, payment_method, order_date, subtotal_ex_vat, vat_total, total_inc_vat)
                     VALUES (${s"ORD-${UUID.randomUUID().toString.take(6)}"},'trade', NULL, $leeds, $cef, $market, $chDist, 'placed','GBP','stripe',$month, 25000, 5000, 30000) RETURNING id"""
            .query[UUID]
            .unique
            .transact(xa)
        ol <-
          sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount) VALUES ($ord,$white,50,500.00,5000.00) RETURNING id"
            .query[UUID]
            .unique
            .transact(xa)
        did <- disp.dispatch(ord, None, None, None, List(DispatchLineInput(ol, 50, Nil))).map(_.toOption.get)
        _ <-
          sql"UPDATE dispatch SET date=$month WHERE id=$did".update.run
            .transact(xa) // date the shipment into the horizon month
        _ <- disp.deliver(did)
        _ <- rev.recognize(did)

        // ---- accuracy: score this owner's forecast vs the actual sell-in ----
        _ <- acc.score(leeds, month, p50, "sell_in")

        // ================= gather data for the report =================
        coverageBranch <- ForecastQueryRepo.coverage(market, month, p50, "branch", None).transact(xa)
        coverageAgent  <- ForecastQueryRepo.coverage(market, month, p50, "agent", None).transact(xa)
        coverageBySku  <- ForecastQueryRepo.coverageBySku(market, month, p50, "market").transact(xa)
        reconcile      <- ForecastQueryRepo.reconcile(market, month, p50).transact(xa)
        outstanding    <- ForecastQueryRepo.outstanding(cyc).transact(xa)
        commits <-
          sql"SELECT product_variant_id, target_date, qty, zone FROM supply_commitment WHERE supplier_id=$volex ORDER BY target_date"
            .query[(UUID, LocalDate, Int, String)]
            .to[List]
            .transact(xa)
        warnings <-
          sql"SELECT zone, committed_qty, demand_qty, delta, source, severity FROM commitment_warning WHERE supplier_id=$volex"
            .query[(String, Int, Int, Int, String, String)]
            .to[List]
            .transact(xa)
        prodRows <-
          sql"SELECT product_variant_id, target_date, committed_qty, produced_qty, shortfall_qty, carried_to_date FROM production_actual WHERE supplier_id=$volex"
            .query[(UUID, LocalDate, Int, Int, Int, Option[LocalDate])]
            .to[List]
            .transact(xa)
        wfWhite      <- WaterfallRepo.waterfall(white, month).transact(xa)
        arBal        <- ledger.balance(rev.ar(cef))
        revBal       <- ledger.balance(rev.revenue(new UUID(0L, 0L)))
        accuracyRows <- ForecastQueryRepo.accuracy(leeds, month, "sell_in").transact(xa)
        names        <- List(black, white, grey).traverse(id => skuName(id).map(id -> _)).map(_.toMap)

        _ <- IO {
          sb.clear()
          sb.append("<html><head><meta charset='utf-8'><title>H6Q simulation</title><style>")
          sb.append(
            "body{font-family:system-ui,Segoe UI,Roboto,sans-serif;margin:2rem;color:#15172a;background:#fafafe}"
          )
          sb.append("h1{color:#962DFF} h2{border-bottom:2px solid #962DFF;padding-bottom:.3rem;margin-top:2rem}")
          sb.append(
            "table{border-collapse:collapse;margin:.6rem 0;font-size:.9rem} th{background:#962DFF;color:#fff;text-align:left;padding:.4rem .7rem}"
          )
          sb.append(
            "td{border-bottom:1px solid #e2e2ee;padding:.35rem .7rem} .ok{color:#0a8a3a;font-weight:700} .warn{color:#c23} </style></head><body>"
          )
          sb.append("<h1>⚡ Hypervolt H6Q — end-to-end simulation</h1>")
          p(
            s"Market UK · horizon <b>${month}</b> · scenario <b>P50</b> · cycle as of ${asOf}. Every number below is produced by the live services and the immutable ledger — the same code paths that run in production."
          )

          h("1. Cast & SKU mix")
          table(
            Seq("Account", "Channel", "Owner"),
            Seq(
              Seq("CEF Leeds (branch of CEF)", "Distributor", "Asha"),
              Seq("CEF York (branch of CEF)", "Distributor", "Ben"),
              Seq("Web Retail", "Retail", "Cara")
            )
          )
          table(
            Seq("Distributor SKU mix", "Share"),
            Seq(Seq(names(black), "50%"), Seq(names(white), "30%"), Seq(names(grey), "20%"))
          )

          h("2. Weekly capture (append-only, per SKU)")
          p(
            "Asha forecast CEF Leeds per SKU and <b>revised</b> 7.5m-black 120→140 (a new version; the prior is retained). Ben entered a single <b>unit count of 200</b> for CEF York — the mix split it per SKU, conserving the total. Cara forecast Web Retail; Hyperview published the retail grey line (no manual estimate there)."
          )
          table(
            Seq("SKU (market, all channels)", "Forecast units", "Source"),
            coverageBySku.map { r =>
              val c = r.hcursor
              Seq(
                names.getOrElse(
                  c.get[String]("product_variant_id")
                    .toOption
                    .flatMap(s => scala.util.Try(UUID.fromString(s)).toOption)
                    .getOrElse(new UUID(0, 0)),
                  c.get[String]("sku").getOrElse("?")
                ),
                c.get[Int]("forecast_qty").getOrElse(0).toString,
                c.get[String]("forecast_source").getOrElse("-")
              )
            }
          )

          h("3. Bottom-up coverage — branch axis ≡ agent axis (reconciles)")
          val branchSum = coverageBranch.map(_.hcursor.get[Int]("forecast_qty").getOrElse(0)).sum
          val agentSum  = coverageAgent.map(_.hcursor.get[Int]("forecast_qty").getOrElse(0)).sum
          p(
            s"Σ branch = <b>$branchSum</b> units · Σ agent = <b>$agentSum</b> units · reconcile ties: <span class='ok'>${reconcile.hcursor.get[Boolean]("ties").getOrElse(false)}</span>"
          )
          table(
            Seq("Branch", "Forecast", "Shipped", "Coverage %"),
            coverageBranch.map { r =>
              val c = r.hcursor;
              Seq(
                c.get[String]("branch_company_id").getOrElse("-").take(8),
                c.get[Int]("forecast_qty").getOrElse(0).toString,
                c.get[Int]("shipped_qty").getOrElse(0).toString,
                c.downField("coverage_pct").focus.flatMap(_.asString).getOrElse("—")
              )
            }
          )

          h("4. Who still owes this week")
          table(
            Seq("Owner", "Outstanding", "Submitted", "Skipped"),
            outstanding
              .filter(r =>
                Set(asha, ben, cara).map(_.toString).contains(r.hcursor.get[String]("forecaster").getOrElse(""))
              )
              .map { r =>
                val c = r.hcursor
                Seq(
                  c.get[String]("name").getOrElse("-"),
                  c.get[Int]("accounts_outstanding").getOrElse(0).toString,
                  c.get[Int]("submitted").getOrElse(0).toString,
                  c.get[Int]("skipped").getOrElse(0).toString
                )
              }
          )

          h("5. Contract-manufacturer firm-commitment window (Volex)")
          p(
            "Free window: establish freely. Flex window: change within ±20% (a +30% move was rejected, +15% admitted). Frozen window: firm — a divergent sales signal raises a <span class='warn'>warning</span>, the PO can't move."
          )
          table(
            Seq("SKU", "Target week", "Firm PO", "Zone"),
            commits.map { case (v, t, q, z) => Seq(names.getOrElse(v, v.toString.take(8)), t.toString, q.toString, z) }
          )
          table(
            Seq("Divergence warning", "Zone", "Committed", "Demand", "Delta", "Severity"),
            warnings.map { case (z, c, d, dl, src, sev) => Seq(src, z, c.toString, d.toString, dl.toString, sev) }
          )

          h("6. Production reality — shortfall carries to the next window")
          table(
            Seq("SKU", "Window", "Committed", "Produced", "Shortfall", "Carried to"),
            prodRows.map {
              case (v, t, c, pr, s, to) =>
                Seq(
                  names.getOrElse(v, v.toString.take(8)),
                  t.toString,
                  c.toString,
                  pr.toString,
                  s.toString,
                  to.map(_.toString).getOrElse("—")
                )
            }
          )

          h("7. Component (parts) buffer — held as parts, not finished goods")
          table(
            Seq("SKU", "Parts on site", "Target (P50)", "Deficit"),
            Seq(Seq(names(black), bufSt.partsOnSite.toString, bufSt.target.toString, bufSt.deficit.toString))
          )
          p(
            "Converting 120 parts → finished goods raises the liability (an invoice for us) via component.converted_to_fg."
          )

          h("8. The demand → revenue waterfall (white SKU) — provable to the ledger")
          val st = wfWhite.hcursor.downField("stages")
          table(
            Seq("Stage", "Units / value"),
            Seq(
              Seq("Sales forecast", st.get[Int]("sales_forecast").getOrElse(0).toString),
              Seq("CM committed", st.get[Int]("cm_committed").getOrElse(0).toString),
              Seq("CM produced", st.get[Int]("cm_produced").getOrElse(0).toString),
              Seq("Delivered (received)", st.get[Int]("delivered").getOrElse(0).toString),
              Seq("Ordered (achieved sales)", st.get[Int]("ordered").getOrElse(0).toString),
              Seq("Shipped (dispatched)", st.get[Int]("shipped").getOrElse(0).toString),
              Seq("Revenue ex-VAT (£)", wfWhite.hcursor.get[String]("revenue_ex_vat").getOrElse("0"))
            )
          )

          h("9. ASC-606 revenue recognised in the immutable ledger (TigerBeetle)")
          table(
            Seq("Account", "Posted (minor units)"),
            Seq(
              Seq(s"AR : CEF (debits)", arBal.debitsPosted.toString),
              Seq(s"Revenue (credits)", revBal.creditsPosted.toString)
            )
          )
          p("These are real TigerBeetle balances — the recognised revenue is provable, not asserted.")

          h("10. Forecast accuracy (owner vs actual sell-in)")
          table(
            Seq("Forecast", "Actual", "Error", "MAPE", "Within 20% margin"),
            accuracyRows.map { r =>
              val c = r.hcursor;
              Seq(
                c.get[Int]("forecast_qty").getOrElse(0).toString,
                c.get[Int]("actual_qty").getOrElse(0).toString,
                c.get[Int]("error").getOrElse(0).toString,
                c.downField("mape").focus.flatMap(_.asString).getOrElse("—"),
                c.downField("within_margin").focus.flatMap(_.asBoolean).map(_.toString).getOrElse("—")
              )
            }
          )

          sb.append("</body></html>")
          val path = new java.io.File("/Users/flavian/projects/hypervolt/conduit/h6q-simulation.html")
          val w    = new java.io.PrintWriter(path); w.write(sb.toString); w.close()
        }
      } yield {
        val branchSum = coverageBranch.map(_.hcursor.get[Int]("forecast_qty").getOrElse(0)).sum
        val agentSum  = coverageAgent.map(_.hcursor.get[Int]("forecast_qty").getOrElse(0)).sum
        // CEF Leeds (Asha): black 140 + white 60 = 200 ; CEF York (Ben, mix of 200): 100+60+40 = 200 ; Web (Cara): white 40 + hv grey 30 = 70
        expect(branchSum == 470) and expect(agentSum == 470) and // reconciliation
          expect(reconcile.hcursor.get[Boolean]("ties").contains(true)) and
          expect(coverageBySku.nonEmpty) and // per-SKU materialised
          expect(cFree.map(_.zone) == Right("free")) and
          expect(cOver == Left("exceeds_flex_tolerance")) and expect(cOk.map(_.admissible) == Right(true)) and
          expect(warn.isDefined) and // frozen divergence warned
          expect(report.committed == 80) and expect(report.shortfall == 20) and expect(report.carriedTo.isDefined) and
          expect(bufSt.deficit == 180) and                  // 500 - 320
          expect(arBal.debitsPosted == BigInt(3000000)) and // £30,000 inc VAT, minor units
          expect(wfWhite.hcursor.downField("stages").get[Int]("shipped").contains(50))
      }
  }
}
