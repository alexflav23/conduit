package com.hypervolt.conduit.notification

import cats.data.NonEmptyList
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// The notifications model (doc 10 §B). Pure ConnectionIO so the fan-out commits in the SAME transaction as the
// projection + the forecast.coverage.updated outbox event — a recompute either fully lands (rows, event,
// notifications) or not at all.
object NotificationRepo {

  // Fan a forward-visibility shift out to every active subscription whose materiality threshold is met. A
  // first-time forecast (prior 0 -> new > 0) is always material. Returns the number of notifications created.
  // External-channel notifications land 'pending' (the delivery relay sends them); in-app land 'sent'.
  def fanoutCoverageUpdated(
      market: UUID,
      period: LocalDate,
      scenario: UUID,
      priorForecast: Int,
      newForecast: Int,
      coveragePct: Option[BigDecimal]
  ): ConnectionIO[Int] = {
    val payload: Json = Json.obj(
      "market_id"      -> market.toString.asJson,
      "period_month"   -> period.toString.asJson,
      "scenario_id"    -> scenario.toString.asJson,
      "prior_forecast" -> priorForecast.asJson,
      "new_forecast"   -> newForecast.asJson,
      "delta"          -> (newForecast - priorForecast).asJson,
      "coverage_pct"   -> coveragePct.asJson
    )
    val subject = "H6Q forward visibility updated"
    val body    = s"Forward demand for $period moved from $priorForecast to $newForecast units."
    sql"""INSERT INTO notification (subscription_id, event_type, subject, body, payload, status)
          SELECT s.id, 'forecast.coverage.updated', $subject, $body, $payload,
                 CASE WHEN s.channel = 'in_app' THEN 'sent' ELSE 'pending' END
          FROM notification_subscription s
          WHERE s.active
            AND 'forecast.coverage.updated' = ANY(s.event_types)
            AND (s.scope_market_id IS NULL OR s.scope_market_id = $market)
            AND (
              ($priorForecast = 0 AND $newForecast > 0)
              OR (abs($newForecast - $priorForecast)::numeric / GREATEST($priorForecast, 1) * 100 >= s.min_change_pct)
            )""".update.run
  }

  def recent(limit: Int): ConnectionIO[List[Json]] =
    sql"""SELECT n.id, s.name, s.channel, n.event_type, n.subject, n.body, n.status, n.created_at
          FROM notification n JOIN notification_subscription s ON s.id = n.subscription_id
          ORDER BY n.created_at DESC LIMIT $limit"""
      .query[(UUID, String, String, String, String, String, String, Instant)]
      .to[List]
      .map(_.map {
        case (id, subName, channel, evt, subject, body, status, at) =>
          Json.obj(
            "id"           -> id.toString.asJson,
            "subscription" -> subName.asJson,
            "channel"      -> channel.asJson,
            "event_type"   -> evt.asJson,
            "subject"      -> subject.asJson,
            "body"         -> body.asJson,
            "status"       -> status.asJson,
            "created_at"   -> at.toString.asJson
          )
      })

  // The delivery relay's worklist: pending external notifications with their channel + endpoint (in_app already
  // lands 'sent' at fan-out, so it is never pending). Each is routed through a NotificationChannel by the relay.
  def pendingForDelivery(limit: Int): ConnectionIO[List[(UUID, String, Option[String], String, String, Json)]] =
    sql"""SELECT n.id, s.channel, s.endpoint, n.subject, n.body, n.payload
          FROM notification n JOIN notification_subscription s ON s.id = n.subscription_id
          WHERE n.status = 'pending' ORDER BY n.created_at LIMIT $limit"""
      .query[(UUID, String, Option[String], String, String, Json)]
      .to[List]

  def markFailed(id: UUID): ConnectionIO[Int] =
    sql"UPDATE notification SET status = 'failed' WHERE id = $id".update.run

  // The delivery relay's claim of pending external notifications (the actual HTTP/email send is wired
  // alongside the notifications model; here we mark them sent so the pipeline is observable end-to-end).
  def markSent(ids: List[UUID]): ConnectionIO[Int] =
    ids match {
      case Nil => 0.pure[ConnectionIO]
      case _ =>
        (fr"UPDATE notification SET status = 'sent', sent_at = now() WHERE" ++
          Fragments.in(fr"id", NonEmptyList.fromListUnsafe(ids))).update.run
    }
}
