package com.hypervolt.conduit.payment

import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import io.circe.Json

// The inbound Stripe queue (doc 13 §payments). The webhook records the raw event idempotently on Stripe's event
// id (ON CONFLICT DO NOTHING — a redelivered webhook records nothing new); the consumer drains 'received' rows.
object StripeInboundRepo {

  // Returns true if this is a newly-recorded event (false = a duplicate Stripe already sent).
  def record(id: String, eventType: String, payload: Json): ConnectionIO[Boolean] =
    sql"""INSERT INTO stripe_event (id, event_type, payload) VALUES ($id, $eventType, $payload)
          ON CONFLICT (id) DO NOTHING""".update.run.map(_ > 0)

  def fetchUnprocessed(limit: Int): ConnectionIO[List[(String, Json)]] =
    sql"""SELECT id, payload FROM stripe_event WHERE status = 'received' ORDER BY received_at LIMIT $limit"""
      .query[(String, Json)]
      .to[List]

  def markProcessed(id: String, result: String): ConnectionIO[Int] =
    sql"""UPDATE stripe_event SET status = 'processed', result = $result, processed_at = now() WHERE id = $id""".update.run

  def markIgnored(id: String, result: String): ConnectionIO[Int] =
    sql"""UPDATE stripe_event SET status = 'ignored', result = $result, processed_at = now() WHERE id = $id""".update.run

  def markFailed(id: String, error: String): ConnectionIO[Int] =
    sql"""UPDATE stripe_event SET status = 'failed', result = $error, processed_at = now() WHERE id = $id""".update.run
}
