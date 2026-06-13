package com.hypervolt.conduit.privacy

import cats.Show
import org.scalacheck.Gen
import scala.util.Try
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

// GDPR crypto-shred (doc 19 §B.3) — the pure-crypto invariants under DsarService. Erasure works by destroying
// the per-subject DEK: once it's gone the ciphertext is mathematically unrecoverable, which is what lets the
// financial skeleton survive while the PII becomes a tombstone. AES-256-GCM, so confidentiality (random IV) and
// integrity (AEAD tag) are both load-bearing and tested here without a DB (DsarSuite covers the DB wiring).
object CryptoShredSpec extends SimpleIOSuite with Checkers {

  private implicit def showAll[A]: Show[A] = Show.fromToString
  private val cs                           = new CryptoShred(CryptoShred.devKey)

  // realistic PII strings: names, emails, addresses — and the empty string + unicode edge cases.
  private val plaintext: Gen[String] =
    Gen.oneOf(
      Gen.alphaNumStr,
      Gen.const(""),
      Gen.const("Ada Lovelace <ada@hypervolt.co.uk>"),
      Gen.const("名前 · Straße 1 · 🔐")
    )

  test("encrypt→decrypt round-trips under the same DEK, for arbitrary plaintext") {
    forall(plaintext) { pt =>
      val dek = cs.newDek()
      expect(cs.decrypt(dek, cs.encrypt(dek, pt)) == pt)
    }
  }

  pureTest("the DEK wrap/unwrap round-trips under the KEK (the only place the DEK is at rest)") {
    val dek = cs.newDek()
    expect(cs.unwrap(cs.wrap(dek)).sameElements(dek))
  }

  // The crux of crypto-shred: destroy the DEK and the ciphertext is unrecoverable. We model "destroyed" as
  // "a different DEK" — no surviving key opens it, so decrypt fails rather than leaking.
  test("once the DEK is gone the ciphertext is unrecoverable (shred), and keys are subject-isolated") {
    forall(plaintext) { pt =>
      val (dek1, dek2) = (cs.newDek(), cs.newDek())
      val ct           = cs.encrypt(dek1, pt)
      expect(!dek1.sameElements(dek2)) and          // every subject gets its own DEK
        expect(Try(cs.decrypt(dek2, ct)).isFailure) // the wrong (or shredded→absent) DEK cannot open it
    }
  }

  pureTest("AEAD integrity: tampering with one byte of ciphertext makes decrypt fail (no silent corruption)") {
    val dek = cs.newDek()
    val ct  = cs.encrypt(dek, "sort code 04-00-04, acct 12345678")
    val flipped = {
      val mid = ct.length / 2
      ct.updated(mid, if (ct(mid) == 'A') 'B' else 'A')
    }
    expect(Try(cs.decrypt(dek, flipped)).isFailure)
  }

  pureTest("confidentiality: encrypting the same plaintext twice yields different ciphertext (random IV)") {
    val dek = cs.newDek()
    val pt  = "repeated-pii-value"
    expect(cs.encrypt(dek, pt) != cs.encrypt(dek, pt))
  }
}
