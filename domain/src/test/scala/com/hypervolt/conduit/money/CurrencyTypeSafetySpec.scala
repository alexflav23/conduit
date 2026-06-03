package com.hypervolt.conduit.money

import scala.reflect.runtime.{universe => ru}
import scala.tools.reflect.ToolBox
import scala.util.Try
import weaver.SimpleIOSuite

// Proves the doc-14 guarantee that cross-currency arithmetic is a *compile* error, not a runtime check.
// We compile snippets with a runtime ToolBox and assert which ones type-check.
object CurrencyTypeSafetySpec extends SimpleIOSuite {

  private val toolbox = ru.runtimeMirror(getClass.getClassLoader).mkToolBox()

  private def compiles(code: String): Boolean =
    Try(toolbox.compile(toolbox.parse(code))).isSuccess

  private val prelude =
    "import com.hypervolt.conduit.money._; import com.hypervolt.conduit.money.Currency._; "

  pureTest("same-currency addition type-checks") {
    expect(compiles(prelude + "Money.of(BigDecimal(1), GBP) + Money.of(BigDecimal(2), GBP)"))
  }

  pureTest("cross-currency addition does NOT type-check (usd + eur)") {
    expect(!compiles(prelude + "Money.of(BigDecimal(1), USD) + Money.of(BigDecimal(2), EUR)"))
  }

  pureTest("convert type-checks only with a rate whose `from` matches the money") {
    val ok =
      prelude + "import java.time.LocalDate; " +
        "Money.of(BigDecimal(1), USD).convert(FxRate(USD, GBP, BigDecimal(\"0.78\"), FxRateType.Spot, \"x\", LocalDate.now), RoundingPolicy.HalfUp)"
    val mismatched =
      prelude + "import java.time.LocalDate; " +
        "Money.of(BigDecimal(1), USD).convert(FxRate(EUR, GBP, BigDecimal(\"0.78\"), FxRateType.Spot, \"x\", LocalDate.now), RoundingPolicy.HalfUp)"
    expect(compiles(ok)) and expect(!compiles(mismatched))
  }
}
