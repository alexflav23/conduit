package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.ledger._
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.time.AccountingPeriod
import com.hypervolt.conduit.time.PeriodScope
import com.hypervolt.conduit.time.PeriodStatus
import com.tigerbeetle.Client
import java.time.ZoneId
import java.util.UUID
import weaver.IOSuite

object LedgerIntegrationSuite extends IOSuite {

  override type Res = Client
  override def maxParallelism: Int            = 1
  override def sharedResource: Resource[IO, Res] = TestTigerBeetle.client

  test("a transfer posts once and is idempotent on its deterministic id (redelivery is a no-op)") { client =>
    val ledger    = TigerBeetleLedger.fromClient[IO](client)
    val gbpLedger = Ledgers.forCurrency(Currency.GBP)
    val ar        = TbIds.accountId("AR:test-party")
    val revenue   = TbIds.accountId("REVENUE:uk")
    val eventId   = UUID.randomUUID()
    val transfer = LedgerTransfer(
      id = TbIds.transferId(eventId, 0),
      debitAccountId = ar,
      creditAccountId = revenue,
      amount = BigInt(58750), // £587.50 in minor units
      ledger = gbpLedger,
      code = LedgerTransferCode.Generic
    )
    for {
      _      <- ledger.createAccounts(
                  List(LedgerAccount(ar, gbpLedger, LedgerAccountCode.Ar), LedgerAccount(revenue, gbpLedger, LedgerAccountCode.Revenue))
                )
      _      <- ledger.postTransfers(List(transfer))
      _      <- ledger.postTransfers(List(transfer)) // redelivery: same deterministic id -> Exists -> no-op
      arBal  <- ledger.balance(ar)
      revBal <- ledger.balance(revenue)
    } yield expect(arBal.debitsPosted == BigInt(58750)) and
      expect(revBal.creditsPosted == BigInt(58750))
  }

  test("posting to a locked accounting period is rejected at the ledger boundary") { client =>
    val poster = new LedgerPoster[IO](TigerBeetleLedger.fromClient[IO](client))
    val locked =
      AccountingPeriod(UUID.randomUUID(), PeriodScope.Month, "2026-06", ZoneId.of("Europe/London"), PeriodStatus.Locked)
    poster.post(locked, Nil).attempt.map(r => expect(r.isLeft))
  }

  test("ledgers are per-currency (distinct ledger ids)") { _ =>
    IO.pure(expect(Ledgers.forCurrency(Currency.GBP) != Ledgers.forCurrency(Currency.USD)))
  }
}
