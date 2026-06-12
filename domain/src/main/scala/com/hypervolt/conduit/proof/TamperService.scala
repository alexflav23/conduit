package com.hypervolt.conduit.proof

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.util.UUID

// The Tamper Sandbox (spec doc 31 §2.5) — NON-PROD ONLY, gated at the route. Each tamper seeds exactly the
// corruption LineageClosureSuite seeds, so the audience watches CTRL-LINEAGE-CLOSURE name the precise break
// live; restore() undoes every stashed tamper in reverse order and the control returns to zero. The stash is
// a table (not memory) so a restart cannot strand a corrupted demo book.
final class TamperService[F[_]: Async](xa: Transactor[F]) {

  def tamper(kind: String): F[Either[String, Json]] =
    kind match {
      case "delete_leg"      => deleteLeg.transact(xa)
      case "orphan_transfer" => orphanTransfer.transact(xa)
      case "strip_reversal"  => stripReversal.transact(xa)
      case other             => s"unknown tamper '$other' (delete_leg | orphan_transfer | strip_reversal)".asLeft[Json].pure[F]
    }

  def restore: F[Json] =
    sql"SELECT id, kind, payload FROM proof_tamper_stash ORDER BY created_at DESC"
      .query[(UUID, String, Json)]
      .to[List]
      .flatMap(_.traverse_ { case (id, kind, payload) => undo(kind, payload) *> drop(id) })
      .transact(xa)
      .as(Json.obj("restored" -> true.asJson))

  // Delete the most recent recognition's COGS mirror pair — a claimed leg with no gl_entry rows.
  private def deleteLeg: ConnectionIO[Either[String, Json]] =
    sql"""SELECT dispatch_id, cogs_transfer_id FROM revenue_recognition
          WHERE cogs_transfer_id IS NOT NULL ORDER BY recognized_at DESC LIMIT 1"""
      .query[(UUID, BigDecimal)]
      .option
      .flatMap {
        case None => "no recognized dispatch to tamper with — seed the demo book first".asLeft[Json].pure[ConnectionIO]
        case Some((dispatchId, tid)) =>
          sql"""SELECT side, account_key, account_role, entity_id::text, currency, amount_minor, phase, posted,
                       transfer_code, event_id::text, occurred_at::text
                FROM gl_entry WHERE tb_transfer_id = $tid"""
            .query[(String, String, Int, Option[String], String, BigDecimal, String, Boolean, Int, String, String)]
            .to[List]
            .flatMap { rows =>
              val payload = Json.obj(
                "tb_transfer_id" -> tid.toBigInt.toString.asJson,
                "rows" -> rows.map {
                  case (side, key, role, ent, ccy, amt, phase, posted, code, ev, at) =>
                    Json.obj(
                      "side"          -> side.asJson,
                      "account_key"   -> key.asJson,
                      "account_role"  -> role.asJson,
                      "entity_id"     -> ent.asJson,
                      "currency"      -> ccy.asJson,
                      "amount_minor"  -> amt.toBigInt.toString.asJson,
                      "phase"         -> phase.asJson,
                      "posted"        -> posted.asJson,
                      "transfer_code" -> code.asJson,
                      "event_id"      -> ev.asJson,
                      "occurred_at"   -> at.asJson
                    )
                }.asJson
              )
              stash("delete_leg", payload) *>
                sql"DELETE FROM gl_entry WHERE tb_transfer_id = $tid".update.run.map(deleted =>
                  Json
                    .obj(
                      "tampered"       -> "delete_leg".asJson,
                      "dispatch_id"    -> dispatchId.toString.asJson,
                      "tb_transfer_id" -> tid.toBigInt.toString.asJson,
                      "rows_deleted"   -> deleted.asJson
                    )
                    .asRight[String]
                )
            }
      }

  // Insert a fabricated two-sided transfer no fact claims — the orphan.
  private def orphanTransfer: ConnectionIO[Either[String, Json]] = {
    val ev  = UUID.randomUUID()
    val tid = BigDecimal(BigInt(ev.toString.replaceAll("-", "").take(24), 16))
    val seedRow = (side: String) =>
      sql"""INSERT INTO gl_entry (tb_transfer_id, side, account_key, account_role, currency, amount_minor,
                                  phase, posted, transfer_code, event_id, occurred_at)
            VALUES ($tid, $side, 'AR:tampered', 1, 'GBP', 12345, 'single', true, 0, $ev, now())""".update.run
    stash("orphan_transfer", Json.obj("event_id" -> ev.toString.asJson)) *>
      seedRow("debit") *> seedRow("credit") *>
      (Json
        .obj("tampered" -> "orphan_transfer".asJson, "event_id" -> ev.toString.asJson)
        .asRight[String])
        .pure[ConnectionIO]
  }

  // Null one reversal leg off a reversed match — an incomplete fact.
  private def stripReversal: ConnectionIO[Either[String, Json]] =
    sql"""SELECT dispatch_id, rev_op_leg_tb_transfer_id FROM ic_match
          WHERE reversed_at IS NOT NULL AND rev_op_leg_tb_transfer_id IS NOT NULL
          ORDER BY reversed_at DESC LIMIT 1"""
      .query[(UUID, BigDecimal)]
      .option
      .flatMap {
        case None =>
          "no reversed flash match to tamper with — void a flash invoice first".asLeft[Json].pure[ConnectionIO]
        case Some((dispatchId, leg)) =>
          stash(
            "strip_reversal",
            Json.obj("dispatch_id" -> dispatchId.toString.asJson, "leg" -> leg.toBigInt.toString.asJson)
          ) *>
            sql"UPDATE ic_match SET rev_op_leg_tb_transfer_id = NULL WHERE dispatch_id = $dispatchId".update.run *>
            (Json
              .obj("tampered" -> "strip_reversal".asJson, "dispatch_id" -> dispatchId.toString.asJson)
              .asRight[String])
              .pure[ConnectionIO]
      }

  private def undo(kind: String, payload: Json): ConnectionIO[Unit] =
    kind match {
      case "delete_leg" =>
        val c   = payload.hcursor
        val tid = BigDecimal(BigInt(c.get[String]("tb_transfer_id").toOption.get))
        c.downField("rows").as[List[Json]].toOption.orEmpty.traverse_ { row =>
          val r = row.hcursor
          sql"""INSERT INTO gl_entry (tb_transfer_id, side, account_key, account_role, entity_id, currency,
                                      amount_minor, phase, posted, transfer_code, event_id, occurred_at)
                VALUES ($tid, ${r.get[String]("side").toOption.get}, ${r.get[String]("account_key").toOption.get},
                        ${r.get[Int]("account_role").toOption.get},
                        ${r.get[Option[String]]("entity_id").toOption.flatten.map(UUID.fromString)},
                        ${r.get[String]("currency").toOption.get},
                        ${BigDecimal(BigInt(r.get[String]("amount_minor").toOption.get))},
                        ${r.get[String]("phase").toOption.get}, ${r.get[Boolean]("posted").toOption.get},
                        ${r.get[Int]("transfer_code").toOption.get},
                        ${UUID.fromString(r.get[String]("event_id").toOption.get)},
                        ${r.get[String]("occurred_at").toOption.get}::timestamptz)
                ON CONFLICT (tb_transfer_id, side) DO NOTHING""".update.run.void
        }
      case "orphan_transfer" =>
        val ev = UUID.fromString(payload.hcursor.get[String]("event_id").toOption.get)
        sql"DELETE FROM gl_entry WHERE event_id = $ev".update.run.void
      case "strip_reversal" =>
        val c   = payload.hcursor
        val d   = UUID.fromString(c.get[String]("dispatch_id").toOption.get)
        val leg = BigDecimal(BigInt(c.get[String]("leg").toOption.get))
        sql"UPDATE ic_match SET rev_op_leg_tb_transfer_id = $leg WHERE dispatch_id = $d".update.run.void
      case _ => ().pure[ConnectionIO]
    }

  private def stash(kind: String, payload: Json): ConnectionIO[Int] =
    sql"INSERT INTO proof_tamper_stash (kind, payload) VALUES ($kind, $payload)".update.run

  private def drop(id: UUID): ConnectionIO[Int] =
    sql"DELETE FROM proof_tamper_stash WHERE id = $id".update.run
}
