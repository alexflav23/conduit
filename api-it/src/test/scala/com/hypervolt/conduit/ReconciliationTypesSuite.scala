package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.close.EvidenceService
import com.hypervolt.conduit.close.PeriodCloseService
import com.hypervolt.conduit.close.ReconciliationService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.ledger.LedgerAccountCode
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.migration.MigrationService
import com.hypervolt.conduit.migration.OpeningLine
import com.hypervolt.conduit.money.Currency
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M13b-GL close-out — the remaining reconciliation TYPES (inventory↔counts, GL↔Xero) on the same gl_entry-backed
// engine, plus the signed evidence pack (doc 14 §5.2–5.3). Both ties are pure Postgres/gl_entry (no TB on the path).
object ReconciliationTypesSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val gbp = Currency.GBP

  private def entityAndPeriod(xa: HikariTransactor[IO]): IO[(UUID, UUID)] =
    (for {
      e <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('Rec','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      pid <-
        sql"""INSERT INTO accounting_period (entity_id, scope, period_key, reporting_tz, status)
                   VALUES ($e, 'month', ${"2026-" + UUID
          .randomUUID()
          .toString
          .take(4)}, 'Europe/London', 'open') RETURNING id"""
          .query[UUID]
          .unique
    } yield (e, pid)).transact(xa)

  test("inventory ↔ counts: matches when the INV ledger equals on-hand at batch cost; a count gap is an exception") {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val mig    = new MigrationService[IO](xa, ledger)
      val recon  = new ReconciliationService[IO](xa)
      for {
        ep <- entityAndPeriod(xa)
        (e, pid) = ep
        v <- (for {
            fam <-
              sql"INSERT INTO product_family (code, name) VALUES (${"f-" + UUID.randomUUID()},'H3') RETURNING id"
                .query[UUID]
                .unique
            vv <-
              sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam, ${"K-" + UUID.randomUUID()}, 'v3') RETURNING id"
                .query[UUID]
                .unique
            loc <- InventoryRepo.createLocation(Some(e), "W", "W")
            _ <- LotBatchRepo.create(
              NewBatch(
                "B-" + UUID.randomUUID(),
                None,
                vv,
                4,
                BigDecimal("50.00"),
                BigDecimal("1.0"),
                "spot",
                None,
                BigDecimal("0"),
                BigDecimal("0"),
                "GBP"
              ),
              LocalDate.parse("2026-01-01")
            )
            // 4 on hand × £50 = £200 physical
            _ <-
              sql"INSERT INTO stock_item (entity_id, product_variant_id, location_id, qty_on_hand) VALUES ($e, $vv, $loc, 4)".update.run
          } yield vv).transact(xa)
        // post £200 to the INV ledger so it ties to the physical count
        _ <- mig.ensureAccounts(e, gbp, List((mig.invAccount(e), LedgerAccountCode.Inv)))
        _ <- mig.postOpeningBalances(
          "sim",
          "lot_batch",
          e,
          gbp,
          List(
            OpeningLine("L-" + UUID.randomUUID(), "INV:" + e, LedgerAccountCode.Inv, debitNormal = true, BigInt(20000))
          )
        )
        matched <- recon.inventoryVsCounts(pid, e, "GBP")
        // shrink the count by one unit (£50) → ledger £200 vs physical £150
        _ <-
          sql"UPDATE stock_item SET qty_on_hand = 3 WHERE entity_id = $e AND product_variant_id = $v".update.run
            .transact(xa)
        broken <- recon.inventoryVsCounts(pid, e, "GBP")
      } yield expect(matched.status == "matched") and
        expect(matched.expected == BigDecimal("200.00") && matched.actual == BigDecimal("200.00")) and
        expect(broken.status == "exception") and expect(broken.variance == BigDecimal("-50.00"))
  }

  test("GL ↔ Xero: an invoice not yet synced to Xero is an exception; the evidence pack bundles it, hash-stable") {
    case (xa, _) =>
      val recon = new ReconciliationService[IO](xa)
      val pack  = new EvidenceService[IO](xa)
      val _     = new PeriodCloseService[IO](xa)
      for {
        ep <- entityAndPeriod(xa)
        (e, pid) = ep
        party <-
          sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('C','wholesaler',true) RETURNING id"
            .query[UUID]
            .unique
            .transact(xa)
        ord <-
          sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
                VALUES (${"O-" + UUID
            .randomUUID()}, 'trade', $e, $party, $party, 'placed', 'GBP', 'invoice', 1000.00, 200.00, 1200.00) RETURNING id"""
            .query[UUID]
            .unique
            .transact(xa)
        // two non-void invoices; only one reached Xero
        _ <-
          sql"INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat, status, xero_invoice_id) VALUES ($ord, ${"I-A-" + UUID
            .randomUUID()}, 1000, 200, 1200, 'open', 'XERO-1')".update.run
            .transact(xa)
        _ <-
          sql"INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat, status, xero_invoice_id) VALUES ($ord, ${"I-B-" + UUID
            .randomUUID()}, 500, 100, 600, 'open', NULL)".update.run
            .transact(xa)
        xero <- recon.glVsXero(pid, e, "GBP")
        p1   <- pack.pack(pid, "2026-06-09T00:00:00Z")
        p2   <- pack.pack(pid, "2099-01-01T00:00:00Z") // different stamp, same content → same hash
      } yield {
        val sha1 = p1.hcursor.get[String]("content_sha256").toOption
        val sha2 = p2.hcursor.get[String]("content_sha256").toOption
        val recs = p1.hcursor.downField("reconciliations").as[List[io.circe.Json]].getOrElse(Nil)
        expect(xero.status == "exception") and
          expect(xero.expected == BigDecimal("1800.00") && xero.actual == BigDecimal("1200.00")) and // £600 unsynced
          expect(xero.variance == BigDecimal("-600.00")) and
          expect(recs.nonEmpty) and                                    // the pack bundled the gl_vs_xero recon
          expect(sha1.exists(_.length == 64)) and expect(sha1 == sha2) // tamper-evident + stamp-independent
      }
  }
}
