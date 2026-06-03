package com.hypervolt.conduit.ledger

import cats.effect.Sync
import com.hypervolt.conduit.time.AccountingPeriod
import com.hypervolt.conduit.time.PeriodGuard

// The ledger-boundary: enforces the period lock (doc 14 §2.4) before any posting reaches TigerBeetle.
// A posting to a `locked` period is rejected regardless of caller. The full Ledger-poster consumer
// (subscribing to ledger events) builds on this in later milestones.
final class LedgerPoster[F[_]: Sync](ledger: TigerBeetleLedger[F]) {

  def post(period: AccountingPeriod, transfers: List[LedgerTransfer]): F[Unit] =
    PeriodGuard.ensurePostable(period) match {
      case Left(err) => Sync[F].raiseError(err)
      case Right(_)  => ledger.postTransfers(transfers)
    }
}
