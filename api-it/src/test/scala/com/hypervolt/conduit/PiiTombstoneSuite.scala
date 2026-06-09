package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.privacy.CryptoShred
import com.hypervolt.conduit.privacy.DsarService
import com.hypervolt.conduit.privacy.PiiTombstoneService
import com.hypervolt.conduit.privacy.PiiVault
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.nio.charset.StandardCharsets
import java.util.UUID
import weaver.IOSuite

// M-NFR.1 follow-on (doc 19 §B.3.3 steps 5–6) — tombstone propagation. A crypto-shred destroys the DEK (vault
// unrecoverable); this proves the SERVED projection columns (party/contact/address/billing) are also overwritten with
// the `«erased»` tombstone by `PiiTombstoneService` (the effect the conduit.crm `pii.shredded` consumer performs),
// while the financial skeleton (order amounts) survives and re-performs. Idempotent: re-applying the tombstone is a
// no-op, so an at-least-once redelivery is safe.
object PiiTombstoneSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val crypto = new CryptoShred(CryptoShred.devKey)

  test("pii.shredded propagates the «erased» tombstone to every served PII column; the financial skeleton survives") {
    xa =>
      val vault     = new PiiVault[IO](xa, crypto)
      val dsar      = new DsarService[IO](xa, vault)
      val tombstone = new PiiTombstoneService[IO](xa)
      val approver  = UUID.randomUUID()
      val erased    = CryptoShred.tombstone
      for {
        // a PERSON subject (is_organization=false → display_name/legal_name are personal data)
        subject <-
          sql"INSERT INTO party (display_name, legal_name, party_type, is_organization) VALUES ('Jane Doe','Jane A. Doe','individual',false) RETURNING id"
            .query[UUID]
            .unique
            .transact(xa)
        // linked personal records: a contact, an address owned by the party, a billing profile
        _ <-
          sql"INSERT INTO contact (party_id, first_name, last_name, email, phone, phone_country) VALUES ($subject,'Jane','Doe','jane@example.com','+447700900000','GB')".update.run
            .transact(xa)
        _ <-
          sql"INSERT INTO address (owner_type, owner_id, type, line1, line2, city, region, postcode, country) VALUES ('party',$subject,'install','1 Privet Drive','Little Whinging','Surrey','Surrey','GU1 1AA','GB')".update.run
            .transact(xa)
        _ <-
          sql"INSERT INTO billing_profile (party_id, billing_name, currency, payment_terms_days) VALUES ($subject,'Jane Doe','GBP',30)".update.run
            .transact(xa)
        // financial skeleton: an order with amounts that MUST survive erasure
        ord <-
          sql"""INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
                VALUES (${"O-" + UUID
            .randomUUID()}, 'trade', $subject, $subject, 'placed', 'GBP', 'invoice', 1000.00, 200.00, 1200.00) RETURNING id"""
            .query[UUID]
            .unique
            .transact(xa)
        _ <- vault.put(subject, "display_name", "Jane Doe")
        // governed erasure: shred the DEK + emit pii.shredded (carries only the subject id)
        reqId <- dsar.requestErasure(subject, "art-17 erasure request", UUID.randomUUID())
        _     <- dsar.approveErasure(reqId, approver)
        // the consumer extracts the subject from the emitted event...
        payload <-
          sql"SELECT payload::text FROM outbox_event WHERE event_type='pii.shredded' AND aggregate_id=$subject"
            .query[String]
            .unique
            .transact(xa)
        env = EventEnvelope(
          "e",
          "pii.shredded",
          1,
          "party",
          subject.toString,
          subject.toString,
          None,
          None,
          None,
          "service:dsar",
          0L,
          payload.getBytes(StandardCharsets.UTF_8)
        )
        extracted = PiiTombstoneService.shreddedSubject(env)
        // ...and propagates the tombstone (idempotent — run twice to prove redelivery-safety)
        _         <- tombstone.propagate(extracted.getOrElse(subject))
        _         <- tombstone.propagate(subject)
        partyName <- sql"SELECT display_name FROM party WHERE id=$subject".query[String].unique.transact(xa)
        legalName <- sql"SELECT legal_name FROM party WHERE id=$subject".query[Option[String]].unique.transact(xa)
        contactRow <-
          sql"SELECT first_name, email, phone FROM contact WHERE party_id=$subject"
            .query[(String, String, String)]
            .unique
            .transact(xa)
        addrRow <-
          sql"SELECT line1, city, postcode FROM address WHERE owner_id=$subject"
            .query[(String, String, String)]
            .unique
            .transact(xa)
        billName <-
          sql"SELECT billing_name FROM billing_profile WHERE party_id=$subject".query[String].unique.transact(xa)
        vaultName <- vault.get(subject, "display_name")
        invTotal  <- sql"""SELECT total_inc_vat FROM "order" WHERE id=$ord""".query[BigDecimal].unique.transact(xa)
      } yield expect(extracted.contains(subject)) and // the event yields the subject, no PII
        expect(partyName == erased) and expect(legalName.contains(erased)) and
        expect(contactRow == (erased, erased, erased)) and // every contact PII field tombstoned
        expect(addrRow == (erased, erased, erased)) and    // address tombstoned
        expect(billName == erased) and                     // billing name tombstoned
        expect(vaultName.contains(erased)) and             // vault unrecoverable (DEK destroyed)
        expect(invTotal == BigDecimal("1200.0000"))        // financial skeleton intact
  }
}
