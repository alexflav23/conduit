package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import com.hypervolt.conduit.notification.NotificationChannel
import com.hypervolt.conduit.notification.NotificationDelivery
import com.hypervolt.conduit.shadow.ShadowGuard
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.util.UUID
import weaver.IOSuite

// P2.6 (doc 34): the notification delivery relay routes pending external notifications through a channel and
// marks them sent/failed — and is shadow-aware: in the dual-run email/webhook are SUPPRESSED (recorded in
// shadow_action, not sent). A failing channel marks the notification failed.
object NotificationDeliverySuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  // a channel that counts deliveries and returns the configured result.
  private def recording(result: Either[String, Unit]): IO[(Ref[IO, Int], NotificationChannel[IO])] =
    Ref.of[IO, Int](0).map { ref =>
      val ch: NotificationChannel[IO] =
        (_: String, _: Option[String], _: String, _: String, _: Json) => ref.update(_ + 1).as(result)
      (ref, ch)
    }

  // seed an email subscription + a pending notification; returns the notification id.
  private def pendingEmail(xa: HikariTransactor[IO]): IO[UUID] =
    (for {
      sub <-
        sql"""INSERT INTO notification_subscription (name, subscriber_type, channel, endpoint, event_types, active)
              VALUES ('Ops', 'user', 'email', 'ops@hypervolt.co.uk', '{order.placed}', true) RETURNING id"""
          .query[UUID]
          .unique
      n <-
        sql"""INSERT INTO notification (subscription_id, event_type, subject, body, payload, status)
              VALUES ($sub, 'order.placed', 'New order', 'An order was placed', '{}'::jsonb, 'pending') RETURNING id"""
          .query[UUID]
          .unique
    } yield n).transact(xa)

  private def status(xa: HikariTransactor[IO], id: UUID): IO[String] =
    sql"SELECT status FROM notification WHERE id = $id".query[String].unique.transact(xa)

  test("delivers a pending external notification through the channel and marks it sent") { xa =>
    for {
      rc <- recording(Right(()))
      (calls, ch) = rc
      id   <- pendingEmail(xa)
      n    <- new NotificationDelivery[IO](xa, ch, ShadowGuard.disabled[IO]).deliverPending()
      sent <- status(xa, id)
      c    <- calls.get
    } yield expect(n >= 1) and expect(c == 1) and expect(sent == "sent")
  }

  test("shadow mode suppresses the send (channel not called), records a shadow_action, still marks sent") { xa =>
    for {
      rc <- recording(Right(()))
      (calls, ch) = rc
      id   <- pendingEmail(xa)
      _    <- new NotificationDelivery[IO](xa, ch, ShadowGuard[IO](xa, shadowOn = true)).deliverPending()
      sent <- status(xa, id)
      c    <- calls.get
      shadowRows <-
        sql"SELECT count(*) FROM shadow_action WHERE action = 'notify.email'".query[Long].unique.transact(xa)
    } yield expect(c == 0) and expect(sent == "sent") and expect(shadowRows >= 1L)
  }

  test("a channel failure marks the notification failed (no silent drop)") { xa =>
    for {
      rc <- recording(Left("smtp down"))
      (_, ch) = rc
      id     <- pendingEmail(xa)
      _      <- new NotificationDelivery[IO](xa, ch, ShadowGuard.disabled[IO]).deliverPending()
      failed <- status(xa, id)
    } yield expect(failed == "failed")
  }
}
