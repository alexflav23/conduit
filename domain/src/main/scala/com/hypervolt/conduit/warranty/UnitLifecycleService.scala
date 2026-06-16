package com.hypervolt.conduit.warranty

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import doobie.Fragments
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate

// The replacement lifecycle of a unit family: the chain from the original first-life unit through every RMA
// replacement, anchored on the original's warranty window (all units in the family share the root warranty_end),
// plus the support tickets that link them. This is the timeline the warranty + genealogy hang off.
final class UnitLifecycleService[F[_]: Async](xa: Transactor[F]) {

  def lifecycle(serial: String): F[Option[Json]] =
    timeline(serial).flatMap {
      case Nil => Option.empty[Json].pure[F]
      case rows =>
        tickets(rows.map(_._1)).map { ts =>
          val units = rows.map {
            case (sn, status, act, wEnd, isRepl) =>
              Json.obj(
                "serial"         -> sn.asJson,
                "status"         -> status.asJson,
                "activated_at"   -> act.map(_.toString).asJson,
                "warranty_end"   -> wEnd.map(_.toString).asJson,
                "is_replacement" -> isRepl.asJson
              )
          }
          Some(
            Json.obj(
              "serial"       -> serial.asJson,
              "root_serial"  -> rows.headOption.map(_._1).asJson,
              "warranty_end" -> rows.flatMap(_._4).headOption.map(_.toString).asJson,
              "family_size"  -> units.size.asJson,
              "timeline"     -> Json.fromValues(units),
              "rma_tickets"  -> Json.fromValues(ts)
            )
          )
        }
    }

  // The whole lineage (root → … → latest) of the family this serial belongs to, ordered oldest-first.
  private def timeline(serial: String): F[List[(String, String, Option[Instant], Option[LocalDate], Boolean)]] =
    sql"""WITH RECURSIVE up AS (
            SELECT id, replaces_serial_unit_id FROM serial_unit WHERE serial_no = $serial
            UNION ALL
            SELECT s.id, s.replaces_serial_unit_id FROM serial_unit s JOIN up ON s.id = up.replaces_serial_unit_id),
          root AS (SELECT id FROM up WHERE replaces_serial_unit_id IS NULL LIMIT 1),
          down AS (
            SELECT id FROM root
            UNION ALL
            SELECT s.id FROM serial_unit s JOIN down ON s.replaces_serial_unit_id = down.id)
          SELECT su.serial_no, su.status, su.activated_at, su.warranty_end, (su.replaces_serial_unit_id IS NOT NULL)
          FROM serial_unit su JOIN down ON down.id = su.id
          ORDER BY su.activated_at NULLS FIRST, su.created_at"""
      .query[(String, String, Option[Instant], Option[LocalDate], Boolean)]
      .to[List]
      .transact(xa)

  // Tickets touching ANY unit in the family — resolved via the matched serial-unit ids (charger_id is raw), so the
  // RMA that links the family shows on every member's lifecycle.
  private def tickets(familySerials: List[String]): F[List[Json]] =
    NonEmptyList.fromList(familySerials) match {
      case None => List.empty[Json].pure[F]
      case Some(serials) =>
        (fr"""SELECT t.ticket_ref, t.original_serial, t.replacement_serial, t.ticket_type, t.reason, t.opened_at::text, t.status
              FROM rma_ticket t WHERE t.original_serial_unit_id IN
                (SELECT id FROM serial_unit WHERE """ ++ Fragments.in(fr"serial_no", serials) ++ fr""")
              OR t.replacement_serial_unit_id IN
                (SELECT id FROM serial_unit WHERE """ ++ Fragments.in(
          fr"serial_no",
          serials
        ) ++ fr""") ORDER BY t.opened_at""")
          .query[
            (String, Option[String], Option[String], Option[String], Option[String], Option[String], Option[String])
          ]
          .to[List]
          .transact(xa)
          .map(_.map {
            case (ref, orig, repl, tType, reason, opened, status) =>
              Json.obj(
                "ticket_ref"         -> ref.asJson,
                "original_serial"    -> orig.asJson,
                "replacement_serial" -> repl.asJson,
                "type"               -> tType.asJson,
                "reason"             -> reason.asJson,
                "opened_at"          -> opened.asJson,
                "status"             -> status.asJson
              )
          })
    }
}
