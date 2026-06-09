package com.hypervolt.conduit.ledger

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.gl.GlEntryRepo
import com.hypervolt.conduit.gl.GlRow
import com.hypervolt.conduit.money.Currency
import doobie._
import doobie.implicits._
import doobie.util.transactor.Transactor
import java.time.Instant
import java.util.UUID

// One account side of a posting — the human key + GL role + owning entity, so the gl_entry mirror is readable
// and reconcilable (TigerBeetle itself only stores the hashed id).
final case class JournalAccount(key: String, role: Int, entity: Option[UUID])

sealed trait JournalPhase
object JournalPhase {
  case object Single      extends JournalPhase
  case object Pending     extends JournalPhase
  case object PostPending extends JournalPhase
  case object VoidPending extends JournalPhase
}

// A double-entry posting: DR `debit` / CR `credit` for `amountMinor` of `currency`, with a deterministic transfer
// id from (eventId, leg). `phase` carries the TigerBeetle two-phase semantics; `pending` points at the pending
// leg a post/void settles; `linkedToNext` chains a cross-currency pair into one atomic group.
final case class Posting(
    eventId: UUID,
    leg: Int,
    debit: JournalAccount,
    credit: JournalAccount,
    currency: Currency,
    amountMinor: BigInt,
    transferCode: Int = LedgerTransferCode.Generic,
    phase: JournalPhase = JournalPhase.Single,
    pending: Option[(UUID, Int)] = None,
    linkedToNext: Boolean = false,
    id: Option[BigInt] = None // an explicit transfer id (for posters with a bespoke id scheme, e.g. migration)
)

// The single posting authority (doc 14 §5): writes the immutable TigerBeetle transfer AND mirrors it into gl_entry
// in one call — so the ledger has a faithful, queryable Postgres read-side. TB is posted first (idempotent on the
// deterministic id), then gl_entry (idempotent on the transfer id) — at-least-once redelivery converges, and the
// gl_vs_tb control proves the mirror never drifts. TB behaviour is byte-identical to a raw postTransfers.
final class Journal[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  def post(occurredAt: Instant, postings: List[Posting]): F[Unit] =
    postings.filter(active) match {
      case Nil => Async[F].unit
      case ps =>
        ledger.postTransfers(ps.map(toTransfer)) *>
          ps.flatTraverse(glRows(_, occurredAt)).flatMap(GlEntryRepo.insert).transact(xa).void
    }

  // A single-leg helper for the common case (one DR/CR transfer).
  def postOne(occurredAt: Instant, posting: Posting): F[Unit] = post(occurredAt, List(posting))

  // Two-phase settle helpers — the realised accounts are resolved from the pending leg's gl rows, so the caller
  // need not re-derive them (commission's post/claw only carry the entry id).
  def postPending(occurredAt: Instant, eventId: UUID, leg: Int, pending: (UUID, Int), amountMinor: BigInt): F[Unit] =
    postOne(occurredAt, settle(eventId, leg, pending, amountMinor, JournalPhase.PostPending))

  def voidPending(occurredAt: Instant, eventId: UUID, leg: Int, pending: (UUID, Int)): F[Unit] =
    postOne(occurredAt, settle(eventId, leg, pending, BigInt(0), JournalPhase.VoidPending))

  private val placeholder = JournalAccount("", 0, None)
  private def settle(eventId: UUID, leg: Int, pending: (UUID, Int), amountMinor: BigInt, phase: JournalPhase): Posting =
    Posting(eventId, leg, placeholder, placeholder, Currency.GBP, amountMinor, phase = phase, pending = Some(pending))

  // A void/post-pending settles a zero-amount transfer; everything else must carry a positive amount to reach TB
  // (the services already filter zero legs — keep that behaviour exactly).
  private def active(p: Posting): Boolean =
    p.phase == JournalPhase.VoidPending || p.phase == JournalPhase.PostPending || p.amountMinor > 0

  private def accId(a: JournalAccount): BigInt = TbIds.accountId(a.key)
  private def pendingId(p: Posting): BigInt    = p.pending.fold(BigInt(0)) { case (e, l) => TbIds.transferId(e, l) }
  private def transferIdOf(p: Posting): BigInt = p.id.getOrElse(TbIds.transferId(p.eventId, p.leg))

  private def toTransfer(p: Posting): LedgerTransfer = {
    val id  = transferIdOf(p)
    val led = Ledgers.forCurrency(p.currency)
    p.phase match {
      case JournalPhase.Single =>
        LedgerTransfer(
          id,
          accId(p.debit),
          accId(p.credit),
          p.amountMinor,
          led,
          p.transferCode,
          flags = if (p.linkedToNext) LedgerFlags.Linked else LedgerFlags.None
        )
      case JournalPhase.Pending =>
        LedgerTransfer(
          id,
          accId(p.debit),
          accId(p.credit),
          p.amountMinor,
          led,
          p.transferCode,
          flags = LedgerFlags.Pending
        )
      case JournalPhase.PostPending =>
        LedgerTransfer(
          id,
          0,
          0,
          p.amountMinor,
          0,
          0,
          flags = LedgerFlags.PostPendingTransfer,
          pendingId = Some(pendingId(p))
        )
      case JournalPhase.VoidPending =>
        LedgerTransfer(id, 0, 0, 0, 0, 0, flags = LedgerFlags.VoidPendingTransfer, pendingId = Some(pendingId(p)))
    }
  }

  private def glRows(p: Posting, at: Instant): ConnectionIO[List[GlRow]] = {
    val tid = BigDecimal(transferIdOf(p))
    p.phase match {
      case JournalPhase.Single =>
        sides(tid, p, p.debit, p.credit, p.currency.code, BigDecimal(p.amountMinor), "single", posted = true, at)
          .pure[ConnectionIO]
      case JournalPhase.Pending =>
        sides(tid, p, p.debit, p.credit, p.currency.code, BigDecimal(p.amountMinor), "pending", posted = false, at)
          .pure[ConnectionIO]
      case JournalPhase.PostPending =>
        resolved(p).map(ps =>
          sides(tid, p, ps._1, ps._2, ps._3, BigDecimal(p.amountMinor), "post_pending", posted = true, at)
        )
      case JournalPhase.VoidPending =>
        resolved(p).map(ps => sides(tid, p, ps._1, ps._2, ps._3, ps._4, "void_pending", posted = false, at))
    }
  }

  // Resolve a post/void-pending's realised accounts from the pending leg's already-recorded gl rows.
  private def resolved(p: Posting): ConnectionIO[(JournalAccount, JournalAccount, String, BigDecimal)] =
    GlEntryRepo.pendingSides(BigDecimal(pendingId(p))).map {
      case Some(ps) =>
        (
          JournalAccount(ps.debit._1, ps.debit._2, ps.debit._3),
          JournalAccount(ps.credit._1, ps.credit._2, ps.credit._3),
          ps.currency,
          ps.amountMinor
        )
      case None =>
        (p.debit, p.credit, p.currency.code, BigDecimal(p.amountMinor))
    }

  private def sides(
      tid: BigDecimal,
      p: Posting,
      debit: JournalAccount,
      credit: JournalAccount,
      currency: String,
      amount: BigDecimal,
      phase: String,
      posted: Boolean,
      at: Instant
  ): List[GlRow] =
    List(
      GlRow(
        tid,
        "debit",
        debit.key,
        debit.role,
        debit.entity,
        currency,
        amount,
        phase,
        posted,
        p.transferCode,
        p.eventId,
        at
      ),
      GlRow(
        tid,
        "credit",
        credit.key,
        credit.role,
        credit.entity,
        currency,
        amount,
        phase,
        posted,
        p.transferCode,
        p.eventId,
        at
      )
    )
}
