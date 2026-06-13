package com.hypervolt.conduit.assurance

import java.security.MessageDigest

// M-Assurance D (spec doc 29): the canonical reproducibility digest. Same data + same code ⇒ bit-identical
// output — proven by a digest that is INVARIANT to row order and to id churn (it digests id-independent
// aggregates: per "table|key" a row count and a numeric sum). The ingest git SHA is folded in, so a digest
// is only ever compared against another at the SAME code+data point: a difference under one (scope, sha)
// means non-determinism or drift, which CTRL-REPRO surfaces. Pure — unit-testable without a DB.
final case class FingerprintLine(table: String, key: String, count: Long, sum: BigDecimal)

object Fingerprint {

  // canonical: sort the lines, render each at fixed precision, sha-256 the join with the ingest SHA prefix.
  def digest(ingestSha: String, lines: List[FingerprintLine]): String = {
    val canonical = (s"sha=$ingestSha" :: lines
      .map(l =>
        s"${l.table}|${l.key}|${l.count}|${l.sum.setScale(4, BigDecimal.RoundingMode.HALF_UP).bigDecimal.toPlainString}"
      )
      .sorted).mkString("\n")
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(canonical.getBytes("UTF-8")).map(b => f"$b%02x").mkString
  }
}
