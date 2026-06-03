package com.hypervolt.conduit.time

import java.time.ZoneId
import java.util.UUID

// Close & lock (doc 14 §2.4). No posting to a `locked` period — enforced at the ledger boundary
// (the ledger poster calls PeriodGuard before posting). A late item posts to the current open period,
// or — if material to a closed period — via a controlled prior-period adjustment (maker-checker + CFO).
sealed abstract class PeriodStatus(val name: String)
object PeriodStatus {
  case object Open   extends PeriodStatus("open")
  case object Closed extends PeriodStatus("closed")
  case object Locked extends PeriodStatus("locked")

  def fromString(s: String): Option[PeriodStatus] = List(Open, Closed, Locked).find(_.name == s)
}

final case class AccountingPeriod(
    entityId: UUID,
    scope: PeriodScope,
    periodKey: String,
    reportingTz: ZoneId,
    status: PeriodStatus
)

object PeriodGuard {
  final case class PeriodLockedError(periodKey: String)
      extends RuntimeException(s"posting to locked period $periodKey is rejected")

  def ensurePostable(period: AccountingPeriod): Either[PeriodLockedError, Unit] =
    period.status match {
      case PeriodStatus.Locked => Left(PeriodLockedError(period.periodKey))
      case _                   => Right(())
    }
}
