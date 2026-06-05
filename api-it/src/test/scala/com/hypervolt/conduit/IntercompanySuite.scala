package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.intercompany.IntercompanyService
import com.hypervolt.conduit.intercompany.StubTaxEngine
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// Intercompany movements (spec doc 13): paired TigerBeetle legs across two entities' currency ledgers (FX_CLEARING
// bridge cross-currency), inventory relieved at SPECIFIC lot landed cost, batch-specific transfer prices with a
// reproducible tp_document, hedge-designated hop FX, and the period lock — all proved against the ledger.
object IntercompanySuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val asOf = LocalDate.parse("2026-09-15")

  private def entity(xa: HikariTransactor[IO], juris: String, ccy: String): IO[UUID] =
    sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES (${s"E-${UUID.randomUUID()}"}, $juris, $ccy, 'operating') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def variant(xa: HikariTransactor[IO]): IO[UUID] =
    (for {
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3') RETURNING id"
          .query[UUID]
          .unique
    } yield v).transact(xa)

  private def lot(xa: HikariTransactor[IO], v: UUID, qty: Int, landed: BigDecimal, ccy: String): IO[UUID] =
    sql"""INSERT INTO lot_batch (batch_no, product_variant_id, qty, unit_cost_usd, fx_rate, fx_basis, landed_unit_cost, currency)
          VALUES (${s"B-${UUID.randomUUID()}"}, $v, $qty, $landed, 1.0, 'spot', $landed, $ccy) RETURNING id"""
      .query[UUID]
      .unique
      .transact(xa)

  private def policy(xa: HikariTransactor[IO], from: UUID, to: UUID, markupPct: BigDecimal): IO[UUID] =
    sql"""INSERT INTO transfer_price_policy (from_entity_id, to_entity_id, method, basis, markup_pct, status, documentation_method)
          VALUES ($from, $to, 'cost_plus', 'landed_cost', $markupPct, 'active', 'cost_plus') RETURNING id"""
      .query[UUID]
      .unique
      .transact(xa)

  private def acct(key: String): BigInt = TbIds.accountId(key)

  test("cross-currency movement posts linked legs through FX_CLEARING; INV relieved at landed cost; IC nets to zero") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val svc    = new IntercompanyService[IO](xa, ledger, StubTaxEngine)
      for {
        sg <- entity(xa, "SG", "USD")
        uk <- entity(xa, "GB", "GBP")
        v  <- variant(xa)
        _ <-
          sql"INSERT INTO exchange_rate (base, quote, rate, rate_type, as_of) VALUES ('USD','GBP',0.80,'spot','2026-09-01')".update.run
            .transact(xa)
        _ <- policy(xa, sg, uk, BigDecimal(15))
        l <- lot(xa, v, 10, BigDecimal("100.00"), "USD")
        r <- svc.move(sg, uk, v, List(l), asOf, UUID.randomUUID())
        res = r.toOption.get
        invSg  <- ledger.balance(acct(s"INV:$sg"))
        margin <- ledger.balance(acct(s"IC_MARGIN:$sg"))
        ic     <- ledger.balance(acct(s"IC:$sg:$uk"))
        fxUsd  <- ledger.balance(acct("FX_CLEARING:USD"))
        invUk  <- ledger.balance(acct(s"INV:$uk"))
      } yield expect(r.isRight) and
        expect(res.isCrossBorder) and expect(res.fxBasis.contains("spot")) and expect(
        res.fxRate == BigDecimal("0.80")
      ) and
        expect(res.transferPriceTotal == BigDecimal("1150.00")) and // 10 × (100 × 1.15)
        expect(invSg.creditsPosted == BigInt(100000)) and           // relieved at landed cost (10 × $100)
        expect(margin.creditsPosted == BigInt(15000)) and           // intragroup margin $150
        expect(ic.debitsPosted == ic.creditsPosted) and             // IC clearing nets to zero
        expect(fxUsd.debitsPosted == BigInt(115000)) and            // TP into FX clearing (USD)
        expect(invUk.debitsPosted == BigInt(92000)) and             // capitalised at TP in GBP (1150 × 0.80)
        expect(res.importTaxStatus == "quoted")                     // cross-border → tax engine called
  }

  test("transfer price is batch-specific and the tp_document reproduces it exactly") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val svc    = new IntercompanyService[IO](xa, ledger, StubTaxEngine)
      for {
        a  <- entity(xa, "GB", "GBP")
        b  <- entity(xa, "GB", "GBP") // same currency + jurisdiction → domestic, no FX, no import tax
        v  <- variant(xa)
        _  <- policy(xa, a, b, BigDecimal(20))
        l1 <- lot(xa, v, 5, BigDecimal("100.00"), "GBP")
        l2 <- lot(xa, v, 5, BigDecimal("120.00"), "GBP")
        r  <- svc.move(a, b, v, List(l1, l2), asOf, UUID.randomUUID())
        res = r.toOption.get
        docs <-
          sql"""SELECT lot_batch_id, lot_landed_unit_cost, markup_or_margin_pct, transfer_unit_price
                      FROM tp_document WHERE intercompany_link_id = ${res.linkId} ORDER BY lot_landed_unit_cost"""
            .query[(UUID, BigDecimal, BigDecimal, BigDecimal)]
            .to[List]
            .transact(xa)
      } yield {
        // two lots, two distinct transfer prices: 100×1.20=120, 120×1.20=144
        val byLot = docs.map { case (_, landed, mm, tp) => (landed, mm, tp) }
        expect(r.isRight) and
          expect(res.importTaxStatus == "n/a") and expect(res.fxBasis.isEmpty) and
          expect(byLot.contains((BigDecimal("100.0000"), BigDecimal("20.0000"), BigDecimal("120.0000")))) and
          expect(byLot.contains((BigDecimal("120.0000"), BigDecimal("20.0000"), BigDecimal("144.0000")))) and
          // reproducibility: TP == landed × (1 + markup/100) re-derived from the stored inputs
          expect(byLot.forall { case (landed, mm, tp) => (landed * (1 + mm / 100)).setScale(2) == tp.setScale(2) })
      }
  }

  test("a designated hedge sets the hop FX to the contracted rate and draws down its notional") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val svc    = new IntercompanyService[IO](xa, ledger, StubTaxEngine)
      for {
        sg <- entity(xa, "SG", "USD")
        uk <- entity(xa, "GB", "GBP")
        v  <- variant(xa)
        _  <- policy(xa, sg, uk, BigDecimal(10))
        _ <-
          sql"""INSERT INTO fx_hedge (entity_id, pair_from, pair_to, contracted_rate, notional, valid_from, valid_to, status)
                    VALUES ($uk, 'USD', 'GBP', 0.75, 100000, '2026-01-01', '2027-01-01', 'active')""".update.run
            .transact(xa)
        l <- lot(xa, v, 10, BigDecimal("100.00"), "USD")
        r <- svc.move(sg, uk, v, List(l), asOf, UUID.randomUUID())
        res = r.toOption.get
        used <- sql"SELECT notional_used FROM fx_hedge WHERE entity_id = $uk".query[BigDecimal].unique.transact(xa)
      } yield expect(r.isRight) and
        expect(res.fxBasis.contains("hedged")) and expect(res.fxRate == BigDecimal("0.75")) and
        expect(used == BigDecimal("1100.0000")) // tpSell = 10 × 110 = 1100 USD exposure drawn
  }

  test("a movement cannot post into a locked accounting period") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val svc    = new IntercompanyService[IO](xa, ledger, StubTaxEngine)
      for {
        a <- entity(xa, "GB", "GBP")
        b <- entity(xa, "GB", "GBP")
        v <- variant(xa)
        _ <- policy(xa, a, b, BigDecimal(10))
        _ <- sql"""INSERT INTO accounting_period (entity_id, scope, period_key, reporting_tz, status)
                   VALUES ($b, 'month', '2026-09', 'Europe/London', 'locked')""".update.run.transact(xa)
        l <- lot(xa, v, 5, BigDecimal("100.00"), "GBP")
        r <- svc.move(a, b, v, List(l), asOf, UUID.randomUUID())
      } yield expect(r.isLeft) and expect(r.left.toOption.exists(_.contains("locked")))
  }
}
