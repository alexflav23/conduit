package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger._
import com.hypervolt.conduit.migration.MigIds
import com.hypervolt.conduit.migration.MigrationService
import com.hypervolt.conduit.migration.OpeningLine
import com.hypervolt.conduit.migration.SyntheticOpeningLots
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.money.Money
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import io.circe.syntax._
import java.util.UUID
import weaver.IOSuite

// M10 part 2 — migration/cutover (doc 18). Idempotent backfill via the event/outbox spine, opening balances
// into TigerBeetle that tie to the penny, and dual-run / cutover reconciliation.
object MigrationCutoverSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val gbp = Currency.GBP

  private def entityWithPeriod(xa: HikariTransactor[IO]): IO[(UUID, UUID)] =
    (for {
      e <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('Mig','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      pid <-
        sql"""INSERT INTO accounting_period (entity_id, scope, period_key, reporting_tz, status)
                   VALUES ($e, 'group', ${s"2026-06-${UUID.randomUUID().toString.take(4)}"}, 'Europe/London', 'open') RETURNING id"""
          .query[UUID]
          .unique
    } yield (e, pid)).transact(xa)

  private def loc(xa: HikariTransactor[IO], e: UUID): IO[UUID] =
    sql"INSERT INTO location (entity_id, code, name) VALUES ($e, ${s"W-${UUID.randomUUID().toString.take(6)}"}, 'W') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  test("backfill is idempotent: a re-run is a no-op — one business row, one outbox event, one migration_record") {
    case (xa, client) =>
      val svc     = new MigrationService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      val batch   = UUID.randomUUID()
      val srcId   = s"MRP-CUST-${UUID.randomUUID()}"
      val payload = Json.obj("customer_name" -> "Acme Trade".asJson, "customer_code" -> srcId.asJson)
      val persist = (cid: UUID) =>
        sql"CREATE TABLE IF NOT EXISTS mig_demo (id uuid PRIMARY KEY)".update.run *>
          sql"INSERT INTO mig_demo (id) VALUES ($cid) ON CONFLICT DO NOTHING".update.run.void
      for {
        first  <- svc.backfill("mrpeasy", "party", srcId, payload, 3, batch, "crm.company.created", "party")(persist)
        second <- svc.backfill("mrpeasy", "party", srcId, payload, 3, batch, "crm.company.created", "party")(persist)
        rows   <- sql"SELECT count(*) FROM mig_demo WHERE id = ${first.conduitId}".query[Long].unique.transact(xa)
        events <-
          sql"SELECT count(*) FROM outbox_event WHERE event_id = ${first.eventId}".query[Long].unique.transact(xa)
        recs <-
          sql"SELECT count(*) FROM migration_record WHERE source='mrpeasy' AND entity_type='party' AND source_id=$srcId"
            .query[Long]
            .unique
            .transact(xa)
      } yield expect(first.migrated) and expect(!second.migrated) and
        expect(first.conduitId == second.conduitId) and
        expect(first.conduitId == MigIds.conduitId("mrpeasy", "party", srcId)) and
        expect(rows == 1L) and expect(events == 1L) and expect(recs == 1L)
  }

  test("opening inventory balances tie MRPeasy's reported value to the penny; the opening trial balance nets to zero") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val svc    = new MigrationService[IO](xa, ledger)
      val batch  = UUID.randomUUID()
      // MRPeasy reports £100.01 of inventory across three variants — a value that does not divide cleanly.
      val reported = Money.of(BigDecimal("100.01"), gbp)
      for {
        ep <- entityWithPeriod(xa)
        (e, _) = ep
        l <- loc(xa, e)
        lots = Vector(
          SyntheticOpeningLots.OpeningLot(UUID.randomUUID(), l, 3, BigDecimal("11.11")),
          SyntheticOpeningLots.OpeningLot(UUID.randomUUID(), l, 5, BigDecimal("7.00")),
          SyntheticOpeningLots.OpeningLot(UUID.randomUUID(), l, 2, BigDecimal("16.00"))
        )
        allocated = SyntheticOpeningLots.reconcile(reported, lots)
        // each synthetic lot must first be recorded (provenance) before its opening transfer can be stamped back
        _ <- allocated.traverse_ { a =>
          val sid = a.lot.variantId.toString
          svc.backfill(
            "mrpeasy",
            "lot_batch",
            sid,
            Json.obj("synthetic_opening" -> true.asJson, "value" -> a.value.amount.toString.asJson),
            4,
            batch,
            "inventory.received",
            "lot_batch",
            List("synthetic_opening")
          )(_ => ().pure[doobie.ConnectionIO])
        }
        _ <- svc.ensureAccounts(e, gbp, List((svc.invAccount(e), LedgerAccountCode.Inv)))
        lines = allocated.toList.map(a =>
          OpeningLine(a.lot.variantId.toString, s"INV:$e", LedgerAccountCode.Inv, debitNormal = true, a.minorAmount)
        )
        _        <- svc.postOpeningBalances("mrpeasy", "lot_batch", e, gbp, lines)
        invBal   <- ledger.balance(svc.invAccount(e))
        residual <- svc.openingTrialBalanceResidual(List(svc.invAccount(e), svc.openingEquity(e)))
        stamped <-
          sql"SELECT count(*) FROM migration_record WHERE batch_id=$batch AND tb_transfer_id IS NOT NULL"
            .query[Long]
            .unique
            .transact(xa)
      } yield expect(invBal.debitsPosted == BigInt(10001)) and // £100.01 to the penny
        expect(residual == BigInt(0)) and                      // Σ debits == Σ credits
        expect(stamped == 3L)
  }

  test("opening transfers are idempotent: re-posting the same opening balance does not double the ledger") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val svc    = new MigrationService[IO](xa, ledger)
      for {
        ep <- entityWithPeriod(xa)
        (e, _) = ep
        _ <- svc.ensureAccounts(e, gbp, List((svc.invAccount(e), LedgerAccountCode.Inv)))
        line =
          OpeningLine(s"L-${UUID.randomUUID()}", s"INV:$e", LedgerAccountCode.Inv, debitNormal = true, BigInt(25000))
        _   <- svc.postOpeningBalances("mrpeasy", "lot_batch", e, gbp, List(line))
        _   <- svc.postOpeningBalances("mrpeasy", "lot_batch", e, gbp, List(line)) // replay — must be a no-op
        bal <- ledger.balance(svc.invAccount(e))
      } yield expect(bal.debitsPosted == BigInt(25000))
  }

  test("dual-run reconciliation: a tie matches; any variance is an exception (zero tolerance on money)") {
    case (xa, client) =>
      val svc = new MigrationService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        ep <- entityWithPeriod(xa)
        (e, pid) = ep
        matched <- svc.reconcile(
          "ar_vs_invoices",
          pid,
          Json.obj("party" -> e.toString.asJson),
          Some("GBP"),
          BigDecimal("1234.56"),
          BigDecimal("1234.56")
        )
        broken <- svc.reconcile(
          "ar_vs_invoices",
          pid,
          Json.obj("party" -> e.toString.asJson),
          Some("GBP"),
          BigDecimal("1234.56"),
          BigDecimal("1234.57")
        )
        excCount <-
          sql"SELECT count(*) FROM reconciliation WHERE period_id=$pid AND status='exception'"
            .query[Long]
            .unique
            .transact(xa)
      } yield expect(matched == "matched") and expect(broken == "exception") and expect(excCount == 1L)
  }

  test("cutover stock validation: units tie exactly and the INV ledger ties to the counted value to the penny") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val svc    = new MigrationService[IO](xa, ledger)
      for {
        ep <- entityWithPeriod(xa)
        (e, pid) = ep
        _ <- svc.ensureAccounts(e, gbp, List((svc.invAccount(e), LedgerAccountCode.Inv)))
        _ <- svc.postOpeningBalances(
          "mrpeasy",
          "lot_batch",
          e,
          gbp,
          List(
            OpeningLine(s"L-${UUID.randomUUID()}", s"INV:$e", LedgerAccountCode.Inv, debitNormal = true, BigInt(50000))
          )
        )
        res <- svc.cutoverStockValidation(
          e,
          pid,
          gbp,
          countedUnits = 10,
          systemUnits = 10,
          countedValueMinor = BigInt(50000)
        )
        (units, value) = res
        mismatch <-
          svc.cutoverStockValidation(e, pid, gbp, countedUnits = 9, systemUnits = 10, countedValueMinor = BigInt(50000))
      } yield expect(units == "matched") and expect(value == "matched") and
        expect(mismatch._1 == "exception") and expect(mismatch._2 == "matched")
  }
}
