package com.hypervolt.conduit.demo

import cats.effect.IO
import cats.syntax.all._
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.commission.CommissionLineInput
import com.hypervolt.conduit.commission.CommissionScheme
import com.hypervolt.conduit.commission.CommissionService
import com.hypervolt.conduit.gl.ConsolidationService
import com.hypervolt.conduit.intercompany.ProcurementCatalogue
import com.hypervolt.conduit.inventory.AllocationService
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.ledger.LedgerAccount
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.Ledgers
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.order.OrderService
import com.hypervolt.conduit.order.PlaceLineInput
import com.hypervolt.conduit.order.PlaceOrderInput
import com.hypervolt.conduit.payment.PaymentService
import com.hypervolt.conduit.pricing.AgreementService
import com.hypervolt.conduit.pricing.RebateService
import com.hypervolt.conduit.pricing.TierBand
import com.hypervolt.conduit.pricing.TierRequest
import com.hypervolt.conduit.returns.RaiseLine
import com.hypervolt.conduit.returns.ReturnService
import com.hypervolt.conduit.revenue.InvoiceReversalService
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.hypervolt.conduit.stockops.StockOpsService
import com.hypervolt.conduit.tax.VatRemittanceService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

final case class DemoControl(code: String, result: String, violations: Long)

final case class DemoSummary(
    orders: Int,
    recognized: Int,
    voids: Int,
    returns: Int,
    payments: Int,
    revenueExVat: BigDecimal,
    operatingCogs: BigDecimal,
    principalMargin: BigDecimal,
    sampleInvoiceId: UUID,
    sampleInvoiceNo: String,
    controls: List[DemoControl]
)

// The demo book of record (spec doc 31 §1): one realistic Hypervolt contract year, seeded EXCLUSIVELY
// through the production write paths — every order tier-bound via OrderService.place, every journal
// written by Journal.post, every approval maker<>checker. A SQL-dump demo is an anti-proof: this book
// passes the controls because the system holds, not because the data was arranged to look like it.
object DemoBook {

  private val market  = UUID.fromString("00000000-0000-0000-0000-00000000316b") // the UK market
  private val channel = UUID.fromString("00000000-0000-0000-0000-0000000031c4") // wholesale channel

  private final case class World(
      sg: UUID,
      op: UUID,
      loc: UUID,
      charger: UUID,
      chargerSku: String,
      accessory: UUID,
      accessorySku: String,
      maker: UUID,
      checker: UUID,
      aurora: UUID,
      chargeworks: UUID,
      northern: UUID,
      brighthome: UUID,
      auroraAgreement: UUID
  )

  private final case class Fulfilled(
      orderId: UUID,
      dispatchId: UUID,
      invoiceId: UUID,
      invoiceNo: String,
      totalIncVat: BigDecimal
  )

  def seed(xa: Transactor[IO], ledger: TigerBeetleLedger[IO]): IO[DemoSummary] = {
    val orders     = new OrderService[IO](xa)
    val alloc      = new AllocationService[IO](xa)
    val dispatch   = new DispatchService[IO](xa)
    val recognise  = new RevenueRecognitionService[IO](xa, ledger)
    val voids      = new InvoiceReversalService[IO](xa, ledger)
    val returns    = new ReturnService[IO](xa, ledger)
    val payments   = new PaymentService[IO](xa, ledger)
    val commission = new CommissionService[IO](xa, ledger, expenseEntity = "uk")
    val stockOps   = new StockOpsService[IO](xa, ledger)
    val rebates    = new RebateService[IO](xa, ledger)
    val vat        = new VatRemittanceService[IO](xa, ledger)

    for {
      _ <- refuseIfSeeded(xa)
      w <- world(xa, ledger, stockOps, returns, commission)

      // ---- governed internal pricing: catalogue v1 (SG -> UK market) ----
      _ <- activateCatalogue(xa, w, BigDecimal("380.00"))

      // ---- Q1–Q3: the trading year, every order through the production placement path ----
      aurora1 <-
        fulfil(xa, orders, alloc, dispatch, recognise, w, w.aurora, List((w.chargerSku, 40)), "invoice", "PO-AUR-0001")
      cw1 <- fulfil(
        xa,
        orders,
        alloc,
        dispatch,
        recognise,
        w,
        w.chargeworks,
        List((w.chargerSku, 6), (w.accessorySku, 10)),
        "stripe",
        "PO-CW-0144"
      )
      north1 <-
        fulfil(xa, orders, alloc, dispatch, recognise, w, w.northern, List((w.chargerSku, 10)), "stripe", "PO-NEV-2210")
      aurora2 <-
        fulfil(xa, orders, alloc, dispatch, recognise, w, w.aurora, List((w.chargerSku, 35)), "invoice", "PO-AUR-0002")
      cw2 <- fulfil(
        xa,
        orders,
        alloc,
        dispatch,
        recognise,
        w,
        w.chargeworks,
        List((w.chargerSku, 8), (w.accessorySku, 6)),
        "stripe",
        "PO-CW-0181"
      )
      bright1 <- fulfil(
        xa,
        orders,
        alloc,
        dispatch,
        recognise,
        w,
        w.brighthome,
        List((w.chargerSku, 2), (w.accessorySku, 5)),
        "stripe",
        "PO-BH-0007"
      )
      north2 <-
        fulfil(xa, orders, alloc, dispatch, recognise, w, w.northern, List((w.chargerSku, 25)), "stripe", "PO-NEV-2287")
      aurora3 <-
        fulfil(xa, orders, alloc, dispatch, recognise, w, w.aurora, List((w.chargerSku, 30)), "invoice", "PO-AUR-0003")
      cw3 <- fulfil(
        xa,
        orders,
        alloc,
        dispatch,
        recognise,
        w,
        w.chargeworks,
        List((w.chargerSku, 4)),
        "stripe",
        "PO-CW-0203"
      )

      // ---- the clearance week: catalogue v2 prices the internal hop BELOW landed (sign-aware pair) ----
      _ <- activateCatalogue(xa, w, BigDecimal("250.00"))
      clear <-
        fulfil(xa, orders, alloc, dispatch, recognise, w, w.northern, List((w.chargerSku, 15)), "stripe", "PO-NEV-2301")
      _ <- activateCatalogue(xa, w, BigDecimal("380.00"))

      aurora4 <-
        fulfil(xa, orders, alloc, dispatch, recognise, w, w.aurora, List((w.chargerSku, 20)), "invoice", "PO-AUR-0004")

      // ---- lifecycle: voids (per-leg reversal incl. the IC pair), returns, payments ----
      _ <-
        voids
          .reverse(north2.invoiceId, "cancellation", "customer cancelled the expansion phase", "demo")
          .flatMap(orRaise("void north2"))
      _ <-
        voids
          .reverse(clear.invoiceId, "correction", "clearance pricing withdrawn", "demo")
          .flatMap(orRaise("void clearance"))

      _ <- restockReturn(xa, returns, w, cw1)
      _ <- scrapReturn(xa, returns, w, aurora2)

      _ <- List(
        (aurora1, BigDecimal(1)),
        (aurora3, BigDecimal(1)),
        (aurora4, BigDecimal(1)),
        (cw1, BigDecimal(1)),
        (cw2, BigDecimal(1)),
        (cw3, BigDecimal(1)),
        (bright1, BigDecimal(1)),
        (north1, BigDecimal("0.5")) // a partial — AR aging shows a live balance
      ).traverse {
        case (f, share) =>
          val amount = (f.totalIncVat * share).setScale(2, scala.math.BigDecimal.RoundingMode.HALF_UP)
          val method = if (share == BigDecimal(1)) "stripe" else "bank"
          payments
            .apply(f.invoiceNo, amount, method, Some(s"demo-${f.invoiceNo}"))
            .flatMap(orRaise(s"pay ${f.invoiceNo}"))
      }
      _ <-
        payments
          .recordPayout("demo-payout-1", w.op, "GBP", BigDecimal("9000.00"), BigDecimal("131.40"))
          .flatMap(orRaise("payout 1"))
      _ <-
        payments
          .recordPayout("demo-payout-2", w.op, "GBP", BigDecimal("4200.00"), BigDecimal("62.20"))
          .flatMap(orRaise("payout 2"))

      // ---- commission: accrue -> post; one claw; one true-up at actual batch cost ----
      scheme <- commissionScheme(xa)
      agent <-
        sql"INSERT INTO sales_agent (name) VALUES ('Demo Agent — South East') RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      _ <- ledger.createAccounts(
        List(
          LedgerAccount(
            commission.payableAccount(agent, "GBP"),
            Ledgers.forCurrency(Currency.GBP),
            LedgerAccountCode.CommPayable
          )
        )
      )
      cl =
        (qty: Int) =>
          CommissionLineInput(BigDecimal("545.00"), BigDecimal("380.00"), qty, "standard", exceptionApproved = false)
      e1 <- commission.accrue(agent, scheme, Some(cw1.orderId), "GBP", demoScheme(scheme), cl(6))
      e2 <- commission.accrue(agent, scheme, Some(cw2.orderId), "GBP", demoScheme(scheme), cl(8))
      e3 <- commission.accrue(agent, scheme, Some(cw3.orderId), "GBP", demoScheme(scheme), cl(4))
      _  <- commission.post(e1, BigDecimal("99.00"))
      _  <- commission.post(e2, BigDecimal("132.00"))
      _  <- commission.claw(e3)
      _ <- commission.trueUp(
        agent,
        scheme,
        Some(cw1.orderId),
        "GBP",
        BigDecimal("10"),
        BigDecimal("545.00"),
        6,
        BigDecimal("99.00"),
        BigDecimal("384.50")
      )

      // ---- ops: a cycle count finds 2-unit shrinkage on accessories (maker <> checker) ----
      onHand <-
        sql"""SELECT COALESCE(SUM(qty_on_hand),0) FROM stock_item
                      WHERE entity_id = ${w.op} AND product_variant_id = ${w.accessory}"""
          .query[Int]
          .unique
          .transact(xa)
      countId <- stockOps.submitCount(w.op, w.loc, List((w.accessory, onHand - 2, onHand)), w.maker)
      _       <- stockOps.approveCount(countId, w.checker).flatMap(orRaise("approve count"))

      // ---- statutory: a VAT remittance against the collected balance ----
      _ <-
        vat
          .remit(w.op, "GB", "2026-Q2", BigDecimal("2000.00"), "GBP", Some("HMRC-DD-2026Q2"), "demo")
          .flatMap(orRaise("vat remit"))

      // ---- ASC 606 variable consideration: Aurora's retrospective rebate accrues, then settles ----
      _ <- rebates.accrueExpected(w.auroraAgreement, w.op, Currency.GBP, Instant.now())
      _ <- rebates.accrue(w.auroraAgreement, w.op, Currency.GBP, Instant.now())
      settlement <-
        rebates
          .proposeSettlement(w.auroraAgreement, w.op, Currency.GBP, "year_end", Instant.now(), w.maker)
          .flatMap(orRaise("propose settlement"))
      _ <- rebates.approveSettlement(settlement, w.checker).flatMap(orRaise("approve settlement"))

      // ---- year-end: a provenanced consolidation run (GBP books, USD presentation) ----
      _ <- sql"""INSERT INTO exchange_rate (base, quote, rate, rate_type, as_of, source)
                 VALUES ('GBP','USD', 1.27000000, 'closing', ${LocalDate.now()}, 'demo: ECB ref')
                 ON CONFLICT DO NOTHING""".update.run.transact(xa)
      _ <- new ConsolidationService[IO](xa).run(LocalDate.now(), "USD", None)

      summary <- summarise(xa, aurora1.invoiceId)
    } yield summary
  }

  // Every automated control in the register, re-performed — the headless proof table.
  def verify(xa: Transactor[IO]): IO[List[DemoControl]] = {
    val runner = new ControlRunner[IO](xa)
    sql"SELECT code FROM control WHERE automated AND evidence_query IS NOT NULL AND status = 'active' ORDER BY code"
      .query[String]
      .to[List]
      .transact(xa)
      .flatMap(
        _.traverse(code =>
          runner.run(code, None).map {
            case Right(o)  => DemoControl(o.code, o.result, o.violations)
            case Left(err) => DemoControl(code, s"error: $err", -1L)
          }
        )
      )
  }

  // ----- the cast -----

  private def refuseIfSeeded(xa: Transactor[IO]): IO[Unit] =
    sql"SELECT count(*) FROM entity WHERE name = 'Hypervolt Procurement SG (demo)'"
      .query[Int]
      .unique
      .transact(xa)
      .flatMap(n =>
        IO.raiseWhen(n > 0)(
          new IllegalStateException(
            "the demo book is already seeded — it is append-only like everything else; reset the stack (docker compose down -v)"
          )
        )
      )

  private def world(
      xa: Transactor[IO],
      ledger: TigerBeetleLedger[IO],
      stockOps: StockOpsService[IO],
      returns: ReturnService[IO],
      commission: CommissionService[IO]
  ): IO[World] =
    for {
      ids <- (for {
          sg <-
            sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type)
                VALUES ('Hypervolt Procurement SG (demo)', 'SG', 'GBP', 'procurement') RETURNING id"""
              .query[UUID]
              .unique
          op  <- sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type, procurement_parent_id)
                VALUES ('Hypervolt UK (demo)', 'GB', 'GBP', 'operating', $sg) RETURNING id""".query[UUID].unique
          loc <- InventoryRepo.createLocation(Some(op), "DEMO-RHENUS-1", "Rhenus Nuneaton (demo)")
          maker <-
            sql"INSERT INTO app_user (keycloak_id, name) VALUES ('demo-maker', 'Demo Maker') RETURNING id"
              .query[UUID]
              .unique
          check <-
            sql"INSERT INTO app_user (keycloak_id, name) VALUES ('demo-checker', 'Demo Checker') RETURNING id"
              .query[UUID]
              .unique
          fam <-
            sql"INSERT INTO product_family (code, name) VALUES ('DEMO-HOME3PRO', 'Home 3 Pro (demo)') RETURNING id"
              .query[UUID]
              .unique
          charger <- sql"""INSERT INTO product_variant (family_id, sku, generation, product_class, is_serialised)
                     VALUES ($fam, 'DEMO-HV3PRO', 'v3', 'charger', true) RETURNING id""".query[UUID].unique
          accFam <-
            sql"INSERT INTO product_family (code, name) VALUES ('DEMO-ACC', 'Accessories (demo)') RETURNING id"
              .query[UUID]
              .unique
          acc <- sql"""INSERT INTO product_variant (family_id, sku, generation, product_class, is_serialised)
                     VALUES ($accFam, 'DEMO-CABLE-7M', 'v3', 'accessory', false) RETURNING id""".query[UUID].unique
          // accessories price from the governed open list (doc 24 §4.5 — they never earn charger tiers)
          _ <-
            sql"""INSERT INTO price_rule (surface, product_variant_id, currency, tax_regime, authorised_price, min_qty, status)
                   VALUES ('customer', $acc, 'GBP', 'GB_STANDARD', 45.00, 1, 'active')""".update.run
          aurora <- party("Aurora Energy (demo)", "energy")
          cw     <- party("ChargeWorks Installations (demo)", "installers")
          nev    <- party("Northern EV Wholesale (demo)", "wholesale")
          bh     <- party("BrightHome Retail (demo)", "retail")
          // Aurora buys on 90-day terms — the credit profile the placement gate checks
          _ <- sql"""INSERT INTO credit_profile (party_id, credit_limit, currency, policy, terms_days)
                   VALUES ($aurora, 250000.00, 'GBP', 'warn', 90)""".update.run
        } yield (sg, op, loc, maker, check, charger, acc, aurora, cw, nev, bh)).transact(xa)
      (sg, op, loc, maker, check, charger, acc, aurora, cw, nev, bh) = ids
      _         <- receiveFromCm(xa, op, loc, charger, acc)
      agreement <- agreements(xa, charger, aurora, cw, nev, bh)
      _         <- preCreateAccounts(ledger, stockOps, returns, commission, op)
    } yield World(
      sg,
      op,
      loc,
      charger,
      "DEMO-HV3PRO",
      acc,
      "DEMO-CABLE-7M",
      maker,
      check,
      aurora,
      cw,
      nev,
      bh,
      agreement
    )

  private def party(name: String, sector: String): doobie.ConnectionIO[UUID] =
    sql"INSERT INTO party (display_name, party_type, is_organization, sector) VALUES ($name, 'wholesaler', true, $sector) RETURNING id"
      .query[UUID]
      .unique

  // The physical genealogy: Luxshare-UK batches at drifting landed cost, every charger serialised.
  private def receiveFromCm(xa: Transactor[IO], op: UUID, loc: UUID, charger: UUID, acc: UUID): IO[Unit] = {
    val batches = List(
      (60, BigDecimal("298.00")),
      (60, BigDecimal("302.00")),
      (50, BigDecimal("305.00")),
      (50, BigDecimal("310.00"))
    )
    (for {
      luxshare <-
        sql"""INSERT INTO supplier (name, billing_currency, supplier_entity, lead_time_days)
                        VALUES ('Luxshare-UK (demo CM)', 'GBP', 'Luxshare Precision UK Ltd', 42) RETURNING id"""
          .query[UUID]
          .unique
      serials <- batches.zipWithIndex.flatTraverse {
        case ((qty, cost), i) =>
          for {
            b <- LotBatchRepo.create(
              NewBatch(
                s"DEMO-LUX-2026-${i + 1}",
                Some(luxshare),
                charger,
                qty,
                cost,
                BigDecimal("1.0"),
                "spot",
                None,
                BigDecimal("4.20"),
                BigDecimal("0"),
                "GBP"
              ),
              LocalDate.now().minusMonths((8 - 2 * i).toLong)
            )
            _ <- InventoryRepo.receive(Some(op), charger, loc, qty)
            ss <- (1 to qty).toList.traverse { n =>
              InventoryRepo
                .addSerial(f"DEMO-HV3-${i + 1}%d${n}%04d", "v3", charger, Some(op), loc)
                .flatTap(LotBatchRepo.assignSerial(_, b))
            }
          } yield ss
      }
      _ <- LotBatchRepo.create(
        NewBatch(
          "DEMO-LUX-ACC-1",
          Some(luxshare),
          acc,
          60,
          BigDecimal("7.50"),
          BigDecimal("1.0"),
          "spot",
          None,
          BigDecimal("0.30"),
          BigDecimal("0"),
          "GBP"
        ),
        LocalDate.now().minusMonths(6)
      )
      _ <- InventoryRepo.receive(Some(op), acc, loc, 60)
    } yield serials).transact(xa).void
  }

  // Nobody types a price (doc 24): every charger price is a governed tier; Aurora's is the
  // cumulative-RETROSPECTIVE agreement — ASC 606 variable consideration from the first unit.
  private def agreements(xa: Transactor[IO], charger: UUID, aurora: UUID, cw: UUID, nev: UUID, bh: UUID): IO[UUID] = {
    val svc  = new AgreementService[IO](xa)
    val from = Instant.now().minusSeconds(3600)
    def flat(name: String, customer: UUID, price: String) =
      svc
        .request(
          TierRequest(
            name,
            "GBP",
            List(customer),
            List(TierBand(charger, 0, None, BigDecimal(price), "GB_STANDARD")),
            from,
            None,
            "per_order",
            Json.obj(),
            Some("demo"),
            UUID.randomUUID()
          )
        )
        .flatMap(svc.activate(_, UUID.randomUUID()))
    for {
      auroraId <- svc.request(
        TierRequest(
          "Aurora Energy framework (demo)",
          "GBP",
          List(aurora),
          List(
            TierBand(charger, 0, Some(99), BigDecimal("600.00"), "GB_STANDARD"),
            TierBand(charger, 100, Some(499), BigDecimal("560.00"), "GB_STANDARD"),
            TierBand(charger, 500, None, BigDecimal("520.00"), "GB_STANDARD")
          ),
          from,
          None,
          "cumulative_retrospective",
          Json.obj("min_commitment_units" -> Json.fromInt(120)),
          Some("demo"),
          UUID.randomUUID()
        )
      )
      _ <- svc.activate(auroraId, UUID.randomUUID())
      _ <- flat("ChargeWorks installer rate (demo)", cw, "545.00")
      _ <-
        svc
          .request(
            TierRequest(
              "Northern EV volume bands (demo)",
              "GBP",
              List(nev),
              List(
                TierBand(charger, 0, Some(9), BigDecimal("600.00"), "GB_STANDARD"),
                TierBand(charger, 10, Some(24), BigDecimal("570.00"), "GB_STANDARD"),
                TierBand(charger, 25, None, BigDecimal("540.00"), "GB_STANDARD")
              ),
              from,
              None,
              "per_order",
              Json.obj(),
              Some("demo"),
              UUID.randomUUID()
            )
          )
          .flatMap(svc.activate(_, UUID.randomUUID()))
      _ <- flat("BrightHome list (demo)", bh, "600.00")
    } yield auroraId
  }

  private def activateCatalogue(xa: Transactor[IO], w: World, chargerTransfer: BigDecimal): IO[Unit] =
    (for {
      id <-
        ProcurementCatalogue
          .propose(
            w.sg,
            market,
            "GBP",
            List(
              ProcurementCatalogue.PriceListLine(w.charger, chargerTransfer),
              ProcurementCatalogue.PriceListLine(w.accessory, BigDecimal("18.00"))
            ),
            w.maker
          )
          .map(_.fold(e => throw new IllegalStateException(e), identity))
      _ <- ProcurementCatalogue.activate(id, w.checker).map(_.left.foreach(e => throw new IllegalStateException(e)))
    } yield ()).transact(xa)

  // The accounts the services do not auto-create (the suites create these too).
  private def preCreateAccounts(
      ledger: TigerBeetleLedger[IO],
      stockOps: StockOpsService[IO],
      returns: ReturnService[IO],
      commission: CommissionService[IO],
      op: UUID
  ): IO[Unit] = {
    val gbp = Ledgers.forCurrency(Currency.GBP)
    ledger
      .createAccounts(
        List(
          LedgerAccount(stockOps.invAccount(op), gbp, LedgerAccountCode.Inv),
          LedgerAccount(stockOps.writeOffAccount(op), gbp, LedgerAccountCode.InvWriteOff),
          LedgerAccount(returns.cosClearing(op), gbp, LedgerAccountCode.CosClearing),
          LedgerAccount(commission.expenseAccount("GBP"), gbp, LedgerAccountCode.CommissionExpense),
          LedgerAccount(TbIds.accountId(s"BANK:$op"), gbp, LedgerAccountCode.Bank)
        )
      )
  }

  private def commissionScheme(xa: Transactor[IO]): IO[UUID] =
    (for {
      sid <-
        sql"""INSERT INTO commission_scheme (name, basis, rate_pct, exception_treatment, valid_from)
                   VALUES ('Demo installer 10% GM', 'gross_margin', 10, 'zero', ${Instant
          .now()
          .minusSeconds(7200)}) RETURNING id"""
          .query[UUID]
          .unique
      _ <- sql"INSERT INTO commission_scheme_assignment (scheme_id) VALUES ($sid)".update.run
    } yield sid).transact(xa)

  private def demoScheme(id: UUID): CommissionScheme =
    CommissionScheme(id, "gross_margin", BigDecimal("10"), "zero", Instant.now().minusSeconds(7200), None)

  // ----- the production fulfilment chain: place (tier-bound) -> allocate -> dispatch -> deliver -> recognize -----

  private def fulfil(
      xa: Transactor[IO],
      orders: OrderService[IO],
      alloc: AllocationService[IO],
      dispatch: DispatchService[IO],
      recognise: RevenueRecognitionService[IO],
      w: World,
      customer: UUID,
      lines: List[(String, Int)],
      paymentMethod: String,
      customerPo: String
  ): IO[Fulfilled] =
    for {
      placed <-
        orders
          .place(
            PlaceOrderInput(
              "trade",
              Some(w.op),
              customer,
              customer,
              channel,
              market,
              "GBP",
              paymentMethod,
              Some(customerPo),
              Some(LocalDate.now().plusDays(14)),
              None,
              lines.map { case (sku, qty) => PlaceLineInput(sku, qty, None, Nil) }
            ),
            Instant.now()
          )
          .map(_.fold(e => throw new IllegalStateException(s"place failed for $customerPo: $e"), identity))
      lineRows <-
        sql"""SELECT ol.id, ol.product_variant_id, ol.qty, pv.is_serialised
                        FROM order_line ol JOIN product_variant pv ON pv.id = ol.product_variant_id
                        WHERE ol.order_id = ${placed.id}"""
          .query[(UUID, UUID, Int, Boolean)]
          .to[List]
          .transact(xa)
      dispatchLines <- lineRows.traverse {
        case (lineId, variant, qty, serialised) =>
          alloc.allocate(lineId, None, w.op, variant, qty, serialised) *> {
            if (!serialised) IO.pure(DispatchLineInput(lineId, qty, Nil))
            else
              // the units the allocator pinned to THIS line — dispatching anything else double-books stock
              sql"""SELECT serial_no FROM serial_unit
                    WHERE order_line_id = $lineId AND status = 'allocated' ORDER BY serial_no"""
                .query[String]
                .to[List]
                .transact(xa)
                .map(DispatchLineInput(lineId, qty, _))
          }
      }
      did <-
        dispatch
          .dispatch(placed.id, None, None, None, dispatchLines)
          .map(_.fold(e => throw new IllegalStateException(s"dispatch failed: $e"), identity))
      _        <- dispatch.deliver(did)
      _        <- recognise.recognize(did).flatMap(orRaise(s"recognize $customerPo"))
      recorded <- sql"SELECT count(*) FROM revenue_recognition WHERE dispatch_id = $did".query[Int].unique.transact(xa)
      _        <- IO.raiseWhen(recorded == 0)(new IllegalStateException(s"recognition row missing for $customerPo"))
      inv <-
        sql"SELECT id, invoice_no, total_inc_vat FROM order_invoice WHERE order_id = ${placed.id} ORDER BY issued_at DESC LIMIT 1"
          .query[(UUID, String, BigDecimal)]
          .unique
          .transact(xa)
    } yield Fulfilled(placed.id, did, inv._1, inv._2, inv._3)

  // ----- returns: one A-grade restock (flash unwind + refund), one DOA scrap -----

  private def restockReturn(xa: Transactor[IO], returns: ReturnService[IO], w: World, f: Fulfilled): IO[Unit] =
    for {
      target <- chargerLine(xa, f)
      rma <- returns.raise(
        f.orderId,
        "full_unit",
        "serial",
        "changed_mind",
        w.maker,
        List(RaiseLine(target._1, target._2, None, 1))
      )
      lineId <- sql"SELECT id FROM rma_line WHERE rma_id = $rma".query[UUID].unique.transact(xa)
      _      <- returns.assess(rma, List((lineId, "a")), w.checker)
      _      <- returns.approve(rma, w.checker, Some("demo: A-grade, restock")).flatMap(orRaise("approve rma"))
      _      <- returns.receive(rma).flatMap(orRaise("receive rma"))
      _      <- returns.disposition(rma, lineId, "restock", None, w.checker).flatMap(orRaise("restock"))
      _      <- returns.refund(rma, "bank").flatMap(orRaise("refund"))
    } yield ()

  private def scrapReturn(xa: Transactor[IO], returns: ReturnService[IO], w: World, f: Fulfilled): IO[Unit] =
    for {
      target <- chargerLine(xa, f)
      rma <-
        returns.raise(f.orderId, "full_unit", "serial", "doa", w.maker, List(RaiseLine(target._1, target._2, None, 1)))
      lineId <- sql"SELECT id FROM rma_line WHERE rma_id = $rma".query[UUID].unique.transact(xa)
      _      <- returns.assess(rma, List((lineId, "c")), w.checker)
      _      <- returns.approve(rma, w.checker, Some("demo: dead on arrival")).flatMap(orRaise("approve doa"))
      _      <- returns.receive(rma).flatMap(orRaise("receive doa"))
      _      <- returns.disposition(rma, lineId, "scrap", None, w.checker).flatMap(orRaise("scrap"))
    } yield ()

  private def chargerLine(xa: Transactor[IO], f: Fulfilled): IO[(UUID, Option[String])] =
    sql"""SELECT ol.id, (SELECT su.serial_no FROM serial_unit su
                         WHERE su.order_line_id = ol.id ORDER BY su.serial_no LIMIT 1)
          FROM order_line ol JOIN product_variant pv ON pv.id = ol.product_variant_id
          WHERE ol.order_id = ${f.orderId} AND pv.is_serialised LIMIT 1"""
      .query[(UUID, Option[String])]
      .unique
      .transact(xa)

  // ----- summary -----

  private def summarise(xa: Transactor[IO], sampleInvoice: UUID): IO[DemoSummary] =
    for {
      controls <- verify(xa)
      sampleNo <- sql"SELECT invoice_no FROM order_invoice WHERE id = $sampleInvoice".query[String].unique.transact(xa)
      counts <- (for {
          orders <- sql"""SELECT count(*) FROM "order" WHERE order_no LIKE 'O%'""".query[Int].unique
          recog  <- sql"SELECT count(*) FROM revenue_recognition".query[Int].unique
          vds    <- sql"SELECT count(*) FROM invoice_reversal".query[Int].unique
          rets   <- sql"SELECT count(*) FROM rma".query[Int].unique
          pays   <- sql"SELECT count(*) FROM payment".query[Int].unique
          rev    <- sql"SELECT COALESCE(SUM(revenue_ex_vat), 0) FROM revenue_recognition".query[BigDecimal].unique
          cogs   <- sql"SELECT COALESCE(SUM(cogs), 0) FROM revenue_recognition".query[BigDecimal].unique
          // the principal's residual: Σ uplift − reversed − unwound (the LRD structure's group margin share)
          margin <-
            sql"""SELECT COALESCE(SUM(CASE WHEN reversed_at IS NULL THEN uplift_total - returned_uplift ELSE 0 END), 0)
                        FROM ic_match""".query[BigDecimal].unique
        } yield (orders, recog, vds, rets, pays, rev, cogs, margin)).transact(xa)
    } yield DemoSummary(
      counts._1,
      counts._2,
      counts._3,
      counts._4,
      counts._5,
      counts._6,
      counts._7,
      counts._8,
      sampleInvoice,
      sampleNo,
      controls
    )

  private def orRaise[A](what: String): Either[_, A] => IO[A] = {
    case Right(a) => IO.pure(a)
    case Left(e)  => IO.raiseError(new IllegalStateException(s"$what failed: $e"))
  }
}
