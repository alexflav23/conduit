package com.hypervolt.conduit.gl

import com.hypervolt.conduit.ledger.LedgerAccountCode
import java.util.UUID
import weaver.SimpleIOSuite

// Pure arithmetic of the gl_entry mirror + ASC-830 consolidation (doc 14 §2.4 / §5) — Docker-free.
object GlMathSpec extends SimpleIOSuite {

  private val e1 = UUID.randomUUID()

  private def line(key: String, role: Int, func: BigDecimal, pres: BigDecimal): ConsLine =
    ConsLine(e1, key, role, GlMath.rateClass(role), "EUR", func, BigDecimal(1), "identity", None, None, pres)

  pureTest("money rounds minor units HALF_UP to 2dp") {
    expect(GlMath.money(BigDecimal(12345)) == BigDecimal("123.45")) and
      expect(GlMath.money(BigDecimal(0)) == BigDecimal("0.00"))
  }

  pureTest("rateClass classifies inventory/equity non_monetary, P&L pnl, the rest monetary") {
    expect(GlMath.rateClass(LedgerAccountCode.Inv) == "non_monetary") and
      expect(GlMath.rateClass(LedgerAccountCode.Revenue) == "pnl") and
      expect(GlMath.rateClass(LedgerAccountCode.Ar) == "monetary")
  }

  pureTest("translate yields functional + presentation, both HALF_UP 2dp") {
    val (func, pres) = GlMath.translate(BigDecimal(123456), BigDecimal("0.8"))
    expect(func == BigDecimal("1234.56")) and expect(pres == BigDecimal("987.65"))
  }

  pureTest("balanced iff Σ debits == Σ credits") {
    expect(GlMath.balanced(BigDecimal(100), BigDecimal(100))) and
      expect(!GlMath.balanced(BigDecimal(100), BigDecimal(101)))
  }

  pureTest("summarise: balanced native books → cta 0, nativeSound, fxClean") {
    val s = GlMath.summarise(
      List(
        line("AR:e1", LedgerAccountCode.Ar, BigDecimal(1000), BigDecimal(1000)),
        line("AP:e1", LedgerAccountCode.Ap, BigDecimal(-400), BigDecimal(-400)),
        line("EQ:e1", LedgerAccountCode.OpeningEquity, BigDecimal(-600), BigDecimal(-600))
      )
    )
    expect(s.assets == BigDecimal(1000)) and
      expect(s.liabilities == BigDecimal(1000)) and
      expect(s.equity == BigDecimal("0.00")) and
      expect(s.cta == BigDecimal("0.00")) and
      expect(s.nativeSound) and
      expect(s.fxClean)
  }

  pureTest("summarise: unbalanced native + an FX_CLEARING residual → not sound, not clean, cta = -equity") {
    val s = GlMath.summarise(
      List(
        line("AR:e1", LedgerAccountCode.Ar, BigDecimal(1000), BigDecimal(1000)),
        line("AP:e1", LedgerAccountCode.Ap, BigDecimal(-400), BigDecimal(-400)),
        line("FX_CLEARING:GBP", LedgerAccountCode.Ar, BigDecimal("0.50"), BigDecimal("0.50"))
      )
    )
    expect(s.assets == BigDecimal("1000.50")) and
      expect(s.liabilities == BigDecimal(400)) and
      expect(s.equity == BigDecimal("600.50")) and
      expect(s.cta == BigDecimal("-600.50")) and
      expect(s.fxResidual == BigDecimal("0.50")) and
      expect(!s.nativeSound) and
      expect(!s.fxClean)
  }
}
