package com.hypervolt.conduit.assurance

import weaver.SimpleIOSuite

// M-Assurance D (doc 29): the canonical digest is invariant to row order and id churn, sensitive to money,
// and bound to the ingest SHA. Pure — no DB.
object FingerprintSpec extends SimpleIOSuite {

  private val a = FingerprintLine("gl_entry", "AR:x:debit", 2, BigDecimal("100.00"))
  private val b = FingerprintLine("gl_entry", "REVENUE:x:credit", 2, BigDecimal("100.00"))

  pureTest("the digest is invariant to the order of the lines (canonical sort)") {
    expect(Fingerprint.digest("sha1", List(a, b)) == Fingerprint.digest("sha1", List(b, a)))
  }

  pureTest("a changed sum changes the digest (money is what reproduces)") {
    val b2 = b.copy(sum = BigDecimal("100.01"))
    expect(Fingerprint.digest("sha1", List(a, b)) != Fingerprint.digest("sha1", List(a, b2)))
  }

  pureTest("a changed count changes the digest") {
    expect(Fingerprint.digest("sha1", List(a, b)) != Fingerprint.digest("sha1", List(a.copy(count = 3), b)))
  }

  pureTest("the same aggregates under a DIFFERENT ingest SHA digest differently") {
    expect(Fingerprint.digest("sha1", List(a, b)) != Fingerprint.digest("sha2", List(a, b)))
  }

  pureTest("equal scale differences do not matter (4dp canonical): 100 == 100.0000") {
    val b3 = b.copy(sum = BigDecimal("100.0000"))
    expect(Fingerprint.digest("sha1", List(a, b)) == Fingerprint.digest("sha1", List(a, b3)))
  }
}
