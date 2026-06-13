package com.hypervolt.conduit.close

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID

// Period close + lock (doc 14 §5). open → closed → locked. The lock is GATED: it cannot proceed while any
// reconciliation for the period is an unsigned exception or any close task is pending — so the books cannot
// lock over a known break. Once locked, posting into the period is rejected at every ledger boundary.
final class PeriodCloseService[F[_]: Async](xa: Transactor[F]) {

  def ensurePeriod(entity: UUID, scope: String, periodKey: String, tz: String): F[UUID] =
    sql"""INSERT INTO accounting_period (entity_id, scope, period_key, reporting_tz, status)
          VALUES ($entity, $scope, $periodKey, $tz, 'open')
          ON CONFLICT (entity_id, scope, period_key) DO UPDATE SET updated_at = now()
          RETURNING id""".query[UUID].unique.transact(xa)

  def close(periodId: UUID, actor: UUID): F[Either[String, Unit]] =
    sql"""UPDATE accounting_period SET status='closed', closed_by=$actor, closed_at=now(), updated_at=now()
          WHERE id=$periodId AND status='open'""".update.run.transact(xa).map {
      case 0 => "period is not open".asLeft[Unit]
      case _ => ().asRight[String]
    }

  // Lock gate (doc 14 §5): refuse while a reconciliation exception is unsigned or a close task is pending; and
  // segregation of duties — the closer cannot also lock (maker ≠ checker).
  def lock(periodId: UUID, actor: UUID): F[Either[String, Unit]] =
    (closeState(periodId), openExceptions(periodId), pendingTasks(periodId)).tupled.transact(xa).flatMap {
      case (None, _, _)                          => "unknown period".asLeft[Unit].pure[F]
      case (Some((s, _)), _, _) if s != "closed" => s"period must be closed before lock (is $s)".asLeft[Unit].pure[F]
      case (Some((_, closer)), _, _) if closer.contains(actor) =>
        "the closer cannot also lock the period (segregation of duties)".asLeft[Unit].pure[F]
      case (_, ex, _) if ex > 0 => s"$ex unsigned reconciliation exception(s) block the lock".asLeft[Unit].pure[F]
      case (_, _, t) if t > 0   => s"$t close task(s) still pending".asLeft[Unit].pure[F]
      case _ =>
        sql"""UPDATE accounting_period SET status='locked', updated_at=now() WHERE id=$periodId AND status='closed'""".update.run
          .transact(xa)
          .as(().asRight[String])
    }

  // The group roll-up gate (spec doc 32 / ASC 810): the group period for a key cannot lock until EVERY
  // operating entity's period for that key is locked. This forces a common group close — the consolidation
  // (doc 14 §2.4) then runs over a fully-locked group period. Names the laggards so close ops can chase them.
  def closeGroup(periodKey: String, actor: UUID): F[Either[String, Unit]] =
    laggingEntities(periodKey).transact(xa).flatMap {
      case Nil =>
        sql"""UPDATE reporting_calendar SET status='locked', locked_by=$actor, locked_at=now()
              WHERE period_key=$periodKey AND status <> 'locked'""".update.run.transact(xa).map {
          case 0 => s"no open group period '$periodKey' to lock".asLeft[Unit]
          case _ => ().asRight[String]
        }
      case names =>
        s"group close blocked: ${names.size} operating entity period(s) not yet locked for $periodKey — ${names.mkString(", ")}"
          .asLeft[Unit]
          .pure[F]
    }

  // operating entities whose period for this key is missing or not yet locked — the roll-up laggards. An entity
  // that did not yet exist when the period ended (created after period_to) never had to close it, so it's excluded.
  private def laggingEntities(periodKey: String): ConnectionIO[List[String]] =
    sql"""SELECT e.name FROM entity e
          JOIN reporting_calendar rc ON rc.period_key = $periodKey
          WHERE e.entity_type = 'operating' AND e.status <> 'retired' AND e.created_at::date <= rc.period_to
            AND NOT EXISTS (SELECT 1 FROM accounting_period p
                            WHERE p.entity_id = e.id AND p.period_key = $periodKey AND p.status = 'locked')
          ORDER BY e.name"""
      .query[String]
      .to[List]

  def postingAllowed(entity: UUID, scope: String, periodKey: String): F[Boolean] =
    sql"""SELECT NOT EXISTS (SELECT 1 FROM accounting_period
            WHERE entity_id=$entity AND scope=$scope AND period_key=$periodKey AND status='locked')"""
      .query[Boolean]
      .unique
      .transact(xa)

  private def closeState(periodId: UUID): ConnectionIO[Option[(String, Option[UUID])]] =
    sql"SELECT status, closed_by FROM accounting_period WHERE id=$periodId".query[(String, Option[UUID])].option

  private def openExceptions(periodId: UUID): ConnectionIO[Long] =
    sql"SELECT count(*) FROM reconciliation WHERE period_id=$periodId AND status='exception' AND signed_off_by IS NULL"
      .query[Long]
      .unique

  private def pendingTasks(periodId: UUID): ConnectionIO[Long] =
    sql"SELECT count(*) FROM period_close_task WHERE period_id=$periodId AND status='pending'".query[Long].unique
}
