package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.ledger._
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.returns._
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

object ReturnsSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val gbp     = Ledgers.forCurrency(Currency.GBP)
  private val maker   = UUID.randomUUID()
  private val checker = UUID.randomUUID()

  private final case class Setup(entity: UUID, order: UUID, line: UUID, serial: String, billTo: UUID, agent: UUID)

  // An invoiced, dispatched, serialised line (£587.50, GB VAT) on a £100 batch, with a posted commission entry.
  private def setup(xa: HikariTransactor[IO]): IO[Setup] = {
    val serial = s"SER-${UUID.randomUUID()}"
    (for {
      e   <- sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id".query[UUID].unique
      fam <- sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'F') RETURNING id".query[UUID].unique
      v   <- sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id".query[UUID].unique
      loc <- InventoryRepo.createLocation(Some(e), "W", "W")
      b   <- LotBatchRepo.create(NewBatch(s"B-${UUID.randomUUID()}", None, v, 1, BigDecimal("100.00"), BigDecimal("1.0"), "spot", None, BigDecimal("0"), BigDecimal("0"), "GBP"), LocalDate.parse("2026-01-01"))
      p   <- sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Cust','wholesaler',true) RETURNING id".query[UUID].unique
      o   <- sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method)
                   VALUES ('ORD-'||nextval('order_no_seq'),'trade',$e,$p,$p,'invoiced','GBP','invoice') RETURNING id""".query[UUID].unique
      line <- sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, tax_regime, status) VALUES ($o,$v,1,587.50,'GB_STANDARD','dispatched') RETURNING id".query[UUID].unique
      sid  <- InventoryRepo.addSerial(serial, "v3", v, Some(e), loc)
      _    <- LotBatchRepo.assignSerial(sid, b)
      _    <- sql"UPDATE serial_unit SET status='dispatched', order_line_id=$line WHERE id=$sid".update.run
      agent <- sql"INSERT INTO sales_agent (name) VALUES ('Agent') RETURNING id".query[UUID].unique
      _    <- sql"""INSERT INTO commission_entry (agent_id, order_id, basis_amount, rate_applied, amount, currency, kind, status)
                    VALUES ($agent, $o, 375.00, 10, 37.50, 'GBP', 'accrual', 'posted')""".update.run
    } yield Setup(e, o, line, serial, p, agent)).transact(xa)
  }

  private def svcFor(xa: HikariTransactor[IO], client: Client): ReturnService[IO] = new ReturnService[IO](xa, TigerBeetleLedger.fromClient[IO](client))

  private def glAccounts(svc: ReturnService[IO], ledger: TigerBeetleLedger[IO], s: Setup): IO[Unit] =
    ledger.createAccounts(List(
      LedgerAccount(svc.arAccount(s.billTo), gbp, LedgerAccountCode.Ar),
      LedgerAccount(svc.revenueAccount(s.entity), gbp, LedgerAccountCode.Revenue),
      LedgerAccount(svc.vatAccount(s.entity), gbp, LedgerAccountCode.Vat),
      LedgerAccount(svc.invAccount(s.entity), gbp, LedgerAccountCode.Inv),
      LedgerAccount(svc.cosClearing(s.entity), gbp, LedgerAccountCode.CosClearing),
      LedgerAccount(svc.commPayable(s.agent), gbp, LedgerAccountCode.CommPayable),
      LedgerAccount(svc.commExpense(s.agent), gbp, LedgerAccountCode.CommPayable)
    ))

  private def eventCount(xa: HikariTransactor[IO], rmaId: UUID, t: String): IO[Long] =
    sql"SELECT count(*) FROM outbox_event WHERE event_type=$t AND aggregate_id=$rmaId".query[Long].unique.transact(xa)

  test("full_unit return: lifecycle emits return.* events; restock re-recognises INV at batch cost; AR + commission reverse; credit note issued") { case (xa, client) =>
    val svc = svcFor(xa, client)
    val ledger = TigerBeetleLedger.fromClient[IO](client)
    for {
      s   <- setup(xa)
      _   <- glAccounts(svc, ledger, s)
      // a forward posted commission earning on COMM_PAYABLE (DR expense, CR payable 37.50)
      _   <- ledger.postTransfers(List(LedgerTransfer(TbIds.transferId(UUID.randomUUID(), 0), svc.commExpense(s.agent), svc.commPayable(s.agent), BigInt(3750), gbp, LedgerTransferCode.Commission)))
      rma <- svc.raise(s.order, "full_unit", "serial", "changed_mind", maker, List(RaiseLine(s.line, Some(s.serial), None, 1)))
      lineId <- sql"SELECT id FROM rma_line WHERE rma_id=$rma".query[UUID].unique.transact(xa)
      _   <- svc.assess(rma, List((lineId, "a")), checker)
      ap  <- svc.approve(rma, checker, None)
      rc  <- svc.receive(rma)
      dp  <- svc.disposition(rma, lineId, "restock", None, checker)
      rf  <- svc.refund(rma, "credit_memo")
      status  <- sql"SELECT status FROM rma WHERE id=$rma".query[String].unique.transact(xa)
      serSt   <- sql"SELECT status FROM serial_unit WHERE serial_no=${s.serial}".query[String].unique.transact(xa)
      cn      <- sql"SELECT count(*) FROM credit_note WHERE rma_id=$rma".query[Long].unique.transact(xa)
      claw    <- sql"SELECT count(*) FROM commission_entry WHERE order_id=${s.order} AND kind='claw'".query[Long].unique.transact(xa)
      invBal  <- ledger.balance(svc.invAccount(s.entity))
      arBal   <- ledger.balance(svc.arAccount(s.billTo))
      payable <- ledger.balance(svc.commPayable(s.agent))
      raised  <- eventCount(xa, rma, "return.raised")
      refunded <- eventCount(xa, rma, "return.refunded")
      dispEv  <- eventCount(xa, rma, "return.restocked")
    } yield expect(ap.isRight) and expect(rc.isRight) and expect(dp.isRight) and expect(rf.isRight) and
      expect(status == "refunded") and expect(serSt == "in_stock") and expect(cn == 1L) and expect(claw == 1L) and
      expect(invBal.debitsPosted == BigInt(10000)) and        // INV re-recognised at £100 batch cost
      expect(arBal.creditsPosted == BigInt(70500)) and        // £587.50 + £117.50 VAT reversed against AR
      expect(payable.debitsPosted == BigInt(3750)) and        // commission clawed back
      expect(raised == 1L) and expect(refunded == 1L) and expect(dispEv == 1L)
  }

  test("maker cannot be checker (self-approval rejected)") { case (xa, client) =>
    val svc = svcFor(xa, client)
    for {
      s  <- setup(xa)
      rma <- svc.raise(s.order, "full_unit", "serial", "changed_mind", maker, List(RaiseLine(s.line, Some(s.serial), None, 1)))
      self <- svc.approve(rma, maker, None)
    } yield expect(self.isLeft)
  }

  test("serials never silently re-enter sellable stock: restock rejected for non-A-grade and for activated units") { case (xa, client) =>
    val svc = svcFor(xa, client)
    val ledger = TigerBeetleLedger.fromClient[IO](client)
    for {
      s   <- setup(xa)
      _   <- ledger.createAccounts(List(LedgerAccount(svc.invAccount(s.entity), gbp, LedgerAccountCode.Inv), LedgerAccount(svc.cosClearing(s.entity), gbp, LedgerAccountCode.CosClearing)))
      rma <- svc.raise(s.order, "full_unit", "serial", "faulty", maker, List(RaiseLine(s.line, Some(s.serial), None, 1)))
      lineId <- sql"SELECT id FROM rma_line WHERE rma_id=$rma".query[UUID].unique.transact(xa)
      _   <- svc.assess(rma, List((lineId, "b")), checker)        // B-grade
      _   <- svc.approve(rma, checker, None)
      _   <- svc.receive(rma)
      bGrade <- svc.disposition(rma, lineId, "restock", None, checker) // B-grade -> rejected
      // now make it A-grade but mark the serial as previously activated
      _   <- sql"UPDATE rma_line SET condition_grade='a' WHERE id=$lineId".update.run.transact(xa)
      _   <- sql"UPDATE serial_unit SET activated_at=now() WHERE serial_no=${s.serial}".update.run.transact(xa)
      activated <- svc.disposition(rma, lineId, "restock", None, checker) // activated -> rejected
      refurb    <- svc.disposition(rma, lineId, "refurbish", None, checker) // allowed
      serSt <- sql"SELECT status FROM serial_unit WHERE serial_no=${s.serial}".query[String].unique.transact(xa)
    } yield expect(bGrade.isLeft) and expect(activated.isLeft) and expect(refurb.isRight) and expect(serSt == "refurbished")
  }

  test("scrap disposition books no inventory re-recognition; serial scrapped") { case (xa, client) =>
    val svc = svcFor(xa, client)
    val ledger = TigerBeetleLedger.fromClient[IO](client)
    for {
      s   <- setup(xa)
      _   <- ledger.createAccounts(List(LedgerAccount(svc.invAccount(s.entity), gbp, LedgerAccountCode.Inv), LedgerAccount(svc.cosClearing(s.entity), gbp, LedgerAccountCode.CosClearing)))
      rma <- svc.raise(s.order, "full_unit", "serial", "damaged_in_transit", maker, List(RaiseLine(s.line, Some(s.serial), None, 1)))
      lineId <- sql"SELECT id FROM rma_line WHERE rma_id=$rma".query[UUID].unique.transact(xa)
      _   <- svc.assess(rma, List((lineId, "scrap")), checker)
      _   <- svc.approve(rma, checker, None)
      _   <- svc.receive(rma)
      _   <- svc.disposition(rma, lineId, "scrap", None, checker)
      serSt  <- sql"SELECT status FROM serial_unit WHERE serial_no=${s.serial}".query[String].unique.transact(xa)
      invBal <- ledger.balance(svc.invAccount(s.entity))
    } yield expect(serSt == "scrapped") and expect(invBal.debitsPosted == BigInt(0))
  }

  test("part_only return retains commission (no claw)") { case (xa, client) =>
    val svc = svcFor(xa, client)
    val ledger = TigerBeetleLedger.fromClient[IO](client)
    for {
      s   <- setup(xa)
      _   <- glAccounts(svc, ledger, s)
      rma <- svc.raise(s.order, "part_only", "component", "wrong_item", maker, List(RaiseLine(s.line, None, Some("cable"), 1)))
      lineId <- sql"SELECT id FROM rma_line WHERE rma_id=$rma".query[UUID].unique.transact(xa)
      _   <- svc.assess(rma, List((lineId, "a")), checker)
      _   <- svc.approve(rma, checker, None)
      _   <- svc.refund(rma, "credit_memo")
      claw <- sql"SELECT count(*) FROM commission_entry WHERE order_id=${s.order} AND kind='claw'".query[Long].unique.transact(xa)
    } yield expect(claw == 0L)
  }

  test("state-machine invariants: refund before approve / disposition before receive / double refund are rejected") { case (xa, client) =>
    val svc = svcFor(xa, client)
    val ledger = TigerBeetleLedger.fromClient[IO](client)
    for {
      s   <- setup(xa)
      _   <- glAccounts(svc, ledger, s)
      rma <- svc.raise(s.order, "full_unit", "serial", "changed_mind", maker, List(RaiseLine(s.line, Some(s.serial), None, 1)))
      lineId <- sql"SELECT id FROM rma_line WHERE rma_id=$rma".query[UUID].unique.transact(xa)
      earlyRefund <- svc.refund(rma, "credit_memo")               // before approve
      _   <- svc.assess(rma, List((lineId, "a")), checker)
      _   <- svc.approve(rma, checker, None)
      earlyDisp <- svc.disposition(rma, lineId, "restock", None, checker) // before receive
      _   <- svc.receive(rma)
      _   <- svc.disposition(rma, lineId, "restock", None, checker)
      _   <- svc.refund(rma, "credit_memo")
      doubleRefund <- svc.refund(rma, "credit_memo")              // second refund
    } yield expect(earlyRefund.isLeft) and expect(earlyDisp.isLeft) and expect(doubleRefund.isLeft)
  }
}
