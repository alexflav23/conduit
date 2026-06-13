package com.hypervolt.conduit.proof

import weaver.SimpleIOSuite

// The law register (doc 30) is the source the Proof Center's law page and the assurance matrix generate from —
// so its structural integrity is itself load-bearing: a law with no pin is an unfalsifiable claim, a malformed
// pin can't be re-performed, a gapped/duplicate id means a law silently went missing. Pure data, pure checks.
object FormalismRegisterSpec extends SimpleIOSuite {

  private val laws = FormalismRegister.laws

  pureTest("every law has non-blank fields and at least one pinning artifact (no unfalsifiable claims)") {
    val bad = laws.filter(l =>
      l.id.trim.isEmpty || l.title.trim.isEmpty || l.statement.trim.isEmpty ||
        l.mechanism.trim.isEmpty || l.origin.trim.isEmpty || l.pins.isEmpty
    )
    expect(bad.isEmpty)
  }

  pureTest("law ids are unique and contiguous L1..Ln (no gap or duplicate in the register)") {
    val ids  = laws.map(_.id)
    val nums = ids.map(_.stripPrefix("L").toIntOption)
    expect(ids.distinct.size == ids.size) and
      expect(nums.forall(_.isDefined)) and
      expect(nums.flatten.sorted == (1 to laws.size).toList)
  }

  pureTest("every pin is a valid kind with a non-blank ref; control pins name a CTRL-* code") {
    val pins  = laws.flatMap(_.pins)
    val kinds = Set("control", "suite", "gate")
    expect(pins.forall(p => kinds(p.kind) && p.ref.trim.nonEmpty)) and
      expect(pins.filter(_.kind == "control").forall(_.ref.startsWith("CTRL-")))
  }

  pureTest("the register carries the full formalism — at least the 14 laws of doc 30") {
    expect(laws.size >= 14)
  }
}
