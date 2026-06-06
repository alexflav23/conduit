package com.hypervolt.conduit.close

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import doobie.postgres.circe.jsonb.implicits._
import java.util.UUID

final case class ControlOutcome(code: String, result: String, violations: Long)

// The ICFR control runner (doc 14 §4/§6). A registered control's `evidence_query` is re-performable SQL that
// returns the VIOLATION COUNT; 0 = pass, >0 = fail. Each run writes a control_run row (the audit evidence).
// The query is operator-authored data on `control` (governed), never user input.
final class ControlRunner[F[_]: Async](xa: Transactor[F]) {

  def run(code: String, periodId: Option[UUID]): F[Either[String, ControlOutcome]] =
    load(code).transact(xa).flatMap {
      case None            => s"unknown control $code".asLeft[ControlOutcome].pure[F]
      case Some((_, None)) => s"control $code has no evidence_query".asLeft[ControlOutcome].pure[F]
      case Some((id, Some(query))) =>
        Fragment.const(query).query[Long].unique.transact(xa).flatMap { violations =>
          val result = if (violations == 0) "pass" else "fail"
          record(id, result, violations, periodId)
            .transact(xa)
            .as(ControlOutcome(code, result, violations).asRight[String])
        }
    }

  private def load(code: String): ConnectionIO[Option[(UUID, Option[String])]] =
    sql"SELECT id, evidence_query FROM control WHERE code=$code AND status='active'"
      .query[(UUID, Option[String])]
      .option

  private def record(controlId: UUID, result: String, violations: Long, periodId: Option[UUID]): ConnectionIO[Int] =
    sql"""INSERT INTO control_run (control_id, result, detail, period_id)
          VALUES ($controlId, $result, ${Json.obj("violations" -> violations.asJson)}, $periodId)""".update.run
}
