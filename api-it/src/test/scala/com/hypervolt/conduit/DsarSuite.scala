package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.close.ControlRunner
import com.hypervolt.conduit.privacy.CryptoShred
import com.hypervolt.conduit.privacy.DsarService
import com.hypervolt.conduit.privacy.PiiVault
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.util.UUID
import weaver.IOSuite

// M-NFR.1 — GDPR right-to-erasure via crypto-shred (doc 19 §B.3). A maker-checker erasure destroys the subject's
// DEK so PII becomes permanently undecryptable («erased» tombstone), while the financial skeleton (order amounts,
// the party row, the ledger) is retained intact and still re-performs. The `pii.shredded` event carries NO PII.
object DsarSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val crypto = new CryptoShred(CryptoShred.devKey)

  test("erasure crypto-shreds PII (irreversible, tombstoned) while the financial skeleton stays intact") { xa =>
    val vault     = new PiiVault[IO](xa, crypto)
    val dsar      = new DsarService[IO](xa, vault)
    val runner    = new ControlRunner[IO](xa)
    val requester = UUID.randomUUID()
    val approver  = UUID.randomUUID()
    for {
      subject <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('subject','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
          .transact(xa)
      // financial skeleton: an order + invoice for this subject, with amounts that must survive erasure
      ord <-
        sql"""INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
              VALUES (${"O-" + UUID
          .randomUUID()}, 'trade', $subject, $subject, 'placed', 'GBP', 'invoice', 1000.00, 200.00, 1200.00) RETURNING id"""
          .query[UUID]
          .unique
          .transact(xa)
      _ <-
        sql"INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat, status) VALUES ($ord, ${"I-" + UUID
          .randomUUID()}, 1000, 200, 1200, 'open')".update.run
          .transact(xa)
      // store PII in the vault
      _      <- vault.put(subject, "display_name", "Jane Doe")
      _      <- vault.put(subject, "email", "jane@example.com")
      before <- vault.get(subject, "display_name")
      // maker-checker: requester cannot approve their own erasure
      reqId    <- dsar.requestErasure(subject, "art-17 erasure request", requester)
      selfDeny <- dsar.approveErasure(reqId, requester)
      ok       <- dsar.approveErasure(reqId, approver)
      // after shred
      afterName  <- vault.get(subject, "display_name")
      afterEmail <- vault.get(subject, "email")
      keyRow <-
        sql"SELECT status, wrapped_dek FROM pii_key WHERE subject_id=$subject"
          .query[(String, Option[String])]
          .unique
          .transact(xa)
      invTotal  <- sql"SELECT total_inc_vat FROM order_invoice WHERE order_id=$ord".query[BigDecimal].unique.transact(xa)
      partyKept <- sql"SELECT count(*) FROM party WHERE id=$subject".query[Long].unique.transact(xa)
      event <-
        sql"SELECT payload::text FROM outbox_event WHERE event_type='pii.shredded' AND aggregate_id=$subject"
          .query[String]
          .unique
          .transact(xa)
      control <- runner.run("CTRL-PII-SHRED", None)
    } yield expect(before.contains("Jane Doe")) and // readable before erasure
      expect(selfDeny.isLeft) and                   // SoD: requester ≠ approver
      expect(ok.isRight) and
      expect(afterName.contains("«erased»")) and expect(
      afterEmail.contains("«erased»")
    ) and                                                                           // tombstoned, undecryptable
      expect(keyRow._1 == "shredded" && keyRow._2.isEmpty) and                      // the DEK is destroyed
      expect(invTotal == BigDecimal("1200.0000")) and expect(partyKept == 1L) and   // skeleton intact
      expect(!event.contains("Jane") && event.contains(subject.toString)) and       // event carries the fact, no PII
      expect(control.toOption.exists(c => c.result == "pass" && c.violations == 0)) // shred is irreversible
  }
}
