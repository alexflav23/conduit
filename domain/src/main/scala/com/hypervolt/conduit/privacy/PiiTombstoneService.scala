package com.hypervolt.conduit.privacy

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.event.EventEnvelope
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.util.UUID

// doc 19 §B.3.3 step 5 — tombstone propagation. A crypto-shred (M-NFR.1) destroys the subject's DEK, so the vault
// ciphertext is permanently unrecoverable; but the denormalised PII the read models serve (party/contact/address/
// billing columns) must ALSO render the `«erased»` tombstone, never stale plaintext. This overwrites those projection
// columns for the subject in one transaction. Idempotent — re-applying the tombstone leaves it «erased» — so an
// at-least-once redelivery of `pii.shredded` is a no-op. The financial skeleton (orders, amounts, ledger) is never
// touched: only personal fields. Org-party names are company names (not personal data, doc 19 §B.3.2) and are left
// intact; secondary structural fields (line2/region/phone_country) are nulled, identifying fields are tombstoned.
final class PiiTombstoneService[F[_]: Async](xa: Transactor[F]) {

  private val tomb = CryptoShred.tombstone

  def propagate(subject: UUID): F[Int] = program(subject).transact(xa)

  private def program(s: UUID): ConnectionIO[Int] =
    (
      sql"UPDATE party SET display_name=$tomb, legal_name=$tomb WHERE id=$s AND is_organization=false".update.run,
      sql"""UPDATE contact SET first_name=$tomb, last_name=$tomb, email=$tomb, phone=$tomb, phone_country=NULL
            WHERE id=$s OR party_id=$s""".update.run,
      sql"""UPDATE address SET line1=$tomb, line2=NULL, city=$tomb, region=NULL, postcode=$tomb
            WHERE (owner_type='party' AND owner_id=$s)
               OR (owner_type='contact' AND owner_id IN (SELECT id FROM contact WHERE id=$s OR party_id=$s))""".update.run,
      sql"UPDATE billing_profile SET billing_name=$tomb WHERE party_id=$s".update.run
    ).mapN(_ + _ + _ + _)
}

object PiiTombstoneService {

  // Pure: pii.shredded → the subject id (the event carries only the fact of erasure, never PII). Unit-testable.
  def shreddedSubject(env: EventEnvelope): Option[UUID] =
    if (env.event_type != "pii.shredded") None
    else
      parse(new String(env.payload, StandardCharsets.UTF_8)).toOption
        .flatMap(_.hcursor.get[String]("subject_id").toOption)
        .flatMap(s => scala.util.Try(UUID.fromString(s)).toOption)
}
