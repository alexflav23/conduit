package com.hypervolt.conduit.gl

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

// One row of the ledger mirror — a single side (debit|credit) of one TigerBeetle transfer (doc 14 §5). Two rows per
// transfer; `posted` marks the rows that count toward the POSTED balance (single + post_pending), so the SQL
// debits/credits per account equal TigerBeetle's debits_posted/credits_posted.
final case class GlRow(
    tbTransferId: BigDecimal,
    side: String,
    accountKey: String,
    accountRole: Int,
    entityId: Option[UUID],
    currency: String,
    amountMinor: BigDecimal,
    phase: String,
    posted: Boolean,
    transferCode: Int,
    eventId: UUID,
    occurredAt: Instant
)

// The two sides of a settled pending transfer, resolved so a post_pending/void_pending realises the SAME accounts.
final case class PendingSides(
    debit: (String, Int, Option[UUID]),
    credit: (String, Int, Option[UUID]),
    currency: String,
    amountMinor: BigDecimal
)

object GlEntryRepo {

  private val insertSql =
    """INSERT INTO gl_entry (tb_transfer_id, side, account_key, account_role, entity_id, currency,
         amount_minor, phase, posted, transfer_code, event_id, occurred_at)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (tb_transfer_id, side) DO NOTHING"""

  def insert(rows: List[GlRow]): ConnectionIO[Int] =
    Update[GlRow](insertSql).updateMany(rows)

  // The realised accounts of a pending transfer (written at accrue time), so its post/void can mirror them.
  def pendingSides(tbTransferId: BigDecimal): ConnectionIO[Option[PendingSides]] =
    sql"""SELECT side, account_key, account_role, entity_id, currency, amount_minor
          FROM gl_entry WHERE tb_transfer_id = $tbTransferId"""
      .query[(String, String, Int, Option[UUID], String, BigDecimal)]
      .to[List]
      .map { rows =>
        (rows.find(_._1 == "debit"), rows.find(_._1 == "credit")) match {
          case (Some(d), Some(c)) => PendingSides((d._2, d._3, d._4), (c._2, c._3, c._4), d._5, d._6).some
          case _                  => none
        }
      }

  // Posted debits/credits for one account — the gl_entry side of the gl_vs_tb tie.
  def postedBalance(accountKey: String): ConnectionIO[(BigDecimal, BigDecimal)] =
    sql"""SELECT
            COALESCE(SUM(amount_minor) FILTER (WHERE side = 'debit'), 0),
            COALESCE(SUM(amount_minor) FILTER (WHERE side = 'credit'), 0)
          FROM gl_entry WHERE account_key = $accountKey AND posted = true"""
      .query[(BigDecimal, BigDecimal)]
      .unique

  // Posted balances per account as of a cutoff (UTC instant) — the native-currency, exact, no-FX rollup.
  def asOfBalances(entity: UUID, asOf: Instant): ConnectionIO[List[(String, Int, String, BigDecimal)]] =
    sql"""SELECT account_key, account_role, currency,
            COALESCE(SUM(CASE side WHEN 'debit' THEN amount_minor ELSE -amount_minor END), 0)
          FROM gl_entry
          WHERE entity_id = $entity AND posted = true AND occurred_at <= $asOf
          GROUP BY account_key, account_role, currency
          HAVING COALESCE(SUM(CASE side WHEN 'debit' THEN amount_minor ELSE -amount_minor END), 0) <> 0
          ORDER BY account_role, account_key"""
      .query[(String, Int, String, BigDecimal)]
      .to[List]

  // Distinct account keys touched by an entity's postings (for the gl_vs_tb sweep across every mirrored account).
  def entityAccounts(entity: UUID): ConnectionIO[List[String]] =
    sql"SELECT DISTINCT account_key FROM gl_entry WHERE entity_id = $entity ORDER BY account_key"
      .query[String]
      .to[List]

  // Every mirrored account (the gl_vs_tb mirror sweeps these against TigerBeetle).
  def allAccounts: ConnectionIO[List[String]] =
    sql"SELECT DISTINCT account_key FROM gl_entry WHERE posted = true ORDER BY account_key".query[String].to[List]

  // Net posted balance (debits − credits, minor units) for one GL role within an entity — e.g. AR for arVsInvoices.
  def roleNet(entity: UUID, role: Int): ConnectionIO[BigDecimal] =
    sql"""SELECT COALESCE(SUM(CASE side WHEN 'debit' THEN amount_minor ELSE -amount_minor END), 0)
          FROM gl_entry WHERE entity_id = $entity AND account_role = $role AND posted = true"""
      .query[BigDecimal]
      .unique

  // The whole-ledger posted totals (Σ debits, Σ credits) — must be equal (double-entry ties by construction).
  def globalTotals: ConnectionIO[(BigDecimal, BigDecimal)] =
    sql"""SELECT COALESCE(SUM(amount_minor) FILTER (WHERE side = 'debit'), 0),
                 COALESCE(SUM(amount_minor) FILTER (WHERE side = 'credit'), 0)
          FROM gl_entry WHERE posted = true"""
      .query[(BigDecimal, BigDecimal)]
      .unique

  // Per-account posted debits/credits for an entity — the trial-balance read-model (no TB fan-out).
  def entityBalances(entity: UUID): ConnectionIO[List[(String, Int, String, BigDecimal, BigDecimal)]] =
    sql"""SELECT account_key, account_role, currency,
            COALESCE(SUM(amount_minor) FILTER (WHERE side = 'debit'), 0),
            COALESCE(SUM(amount_minor) FILTER (WHERE side = 'credit'), 0)
          FROM gl_entry WHERE entity_id = $entity AND posted = true
          GROUP BY account_key, account_role, currency
          ORDER BY account_role, account_key"""
      .query[(String, Int, String, BigDecimal, BigDecimal)]
      .to[List]
}
