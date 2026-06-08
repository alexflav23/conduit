package com.hypervolt.conduit.ledger

import com.hypervolt.conduit.money.Currency

// One TigerBeetle ledger per currency (doc 01 §5) — TB ledgers are single-currency. Cross-currency is two
// linked transfers through an FX-clearing account. Ledger ids are stable (sorted) so they never shift.
object Ledgers {
  private val codes: Map[String, Int] =
    Currency.all.map(_.code).sorted.zipWithIndex.map { case (code, i) => code -> (700 + i) }.toMap

  def forCurrency(c: Currency): Int = codes(c.code)
}

// TigerBeetle account `code` = the GL account role (doc 04 §Ledger). u16 categorisation.
object LedgerAccountCode {
  val Ar: Int             = 1  // receivable per trade customer
  val Ap: Int             = 2  // payable per supplier
  val Inv: Int            = 3  // inventory asset per entity (specific-identification batch cost)
  val CosClearing: Int    = 4  // cost-of-sales clearing — relieved on delivery, reclassified downstream
  val Vat: Int            = 5  // tax control
  val CommPayable: Int    = 6  // commission payable per agent
  val Intercompany: Int   = 7  // intercompany clearing
  val FxClearing: Int     = 8  // cross-currency bridge
  val Revenue: Int        = 9
  val OpeningEquity: Int  = 10 // OPENING_BALANCE_EQUITY contra — migration opening balances post against it (doc 18 §2)
  val IcMargin: Int       = 11 // intragroup margin on an intercompany sell leg — eliminated on consolidation (doc 13 §7)
  val Bank: Int           = 12 // cash at bank per entity — receives settled AR (doc 13 §payments)
  val StripeClearing: Int = 13 // Stripe balance per entity — gross on payment, relieved net+fee on payout
  val FeeExpense: Int     = 14 // payment-processor fees (Stripe) → P&L
}

object LedgerTransferCode {
  val Generic: Int      = 1
  val Commission: Int   = 10
  val Opening: Int      = 20 // migration opening-balance transfer (doc 18 §2)
  val Intercompany: Int = 30 // intercompany movement leg (doc 13 §3.3)
  val Payment: Int      = 40 // cash application — settles AR (doc 13 §payments)
}

// TigerBeetle transfer flags (two-phase + linking).
object LedgerFlags {
  val None: Int                = 0
  val Linked: Int              = 1
  val Pending: Int             = 2
  val PostPendingTransfer: Int = 4
  val VoidPendingTransfer: Int = 8
}
