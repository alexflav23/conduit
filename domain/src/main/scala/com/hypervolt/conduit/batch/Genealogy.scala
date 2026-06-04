package com.hypervolt.conduit.batch

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID

// Serial lifecycle + genealogy (doc 02 §G). Append-only events; full bidirectional traceability:
// serial → batch → order → customer → lifecycle, and batch → all serials/holders.
object Genealogy {

  def record(serialId: UUID, eventType: String, refType: Option[String], refId: Option[UUID], actor: Option[UUID]): ConnectionIO[Int] =
    sql"""INSERT INTO unit_lifecycle_event (serial_unit_id, event_type, ref_type, ref_id, actor_user_id)
          VALUES ($serialId, $eventType, $refType, $refId, $actor)""".update.run

  // serial → batch → order → customer → lifecycle timeline
  def ofSerial(serialNo: String): ConnectionIO[Option[Json]] =
    sql"""SELECT s.id, s.serial_no, s.status, s.lot_batch_id, b.batch_no, b.landed_unit_cost,
                 s.order_line_id, o.order_no, o.sold_to_party_id
          FROM serial_unit s
          LEFT JOIN lot_batch b ON b.id = s.lot_batch_id
          LEFT JOIN order_line ol ON ol.id = s.order_line_id
          LEFT JOIN "order" o ON o.id = ol.order_id
          WHERE s.serial_no = $serialNo"""
      .query[(UUID, String, String, Option[UUID], Option[String], Option[BigDecimal], Option[UUID], Option[String], Option[UUID])]
      .option
      .flatMap {
        case None => Option.empty[Json].pure[ConnectionIO]
        case Some((sid, serial, status, batchId, batchNo, landed, _, orderNo, soldTo)) =>
          events(sid).map { evs =>
            Some(
              Json.obj(
                "serial_no"        -> serial.asJson,
                "status"           -> status.asJson,
                "batch"            -> batchId.map(_ => Json.obj("batch_no" -> batchNo.asJson, "landed_unit_cost" -> landed.map(_.toString).asJson)).getOrElse(Json.Null),
                "order_no"         -> orderNo.asJson,
                "customer_party"   -> soldTo.map(_.toString).asJson,
                "lifecycle"        -> Json.fromValues(evs)
              )
            )
          }
      }

  private def events(serialId: UUID): ConnectionIO[List[Json]] =
    sql"SELECT event_type, occurred_at FROM unit_lifecycle_event WHERE serial_unit_id = $serialId ORDER BY occurred_at"
      .query[(String, Instant)]
      .to[List]
      .map(_.map { case (t, at) => Json.obj("event" -> t.asJson, "at" -> at.toString.asJson) })

  // batch → all serials/holders (recall / warranty lookup)
  def serialsOfBatch(batchNo: String): ConnectionIO[List[Json]] =
    sql"""SELECT s.serial_no, s.status, s.company_id, s.order_line_id
          FROM serial_unit s JOIN lot_batch b ON b.id = s.lot_batch_id
          WHERE b.batch_no = $batchNo ORDER BY s.serial_no"""
      .query[(String, String, Option[UUID], Option[UUID])]
      .to[List]
      .map(_.map { case (serial, status, holder, line) =>
        Json.obj("serial_no" -> serial.asJson, "status" -> status.asJson, "holder_party" -> holder.map(_.toString).asJson, "order_line" -> line.map(_.toString).asJson)
      })
}
