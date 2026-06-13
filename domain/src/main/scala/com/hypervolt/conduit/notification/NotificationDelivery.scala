package com.hypervolt.conduit.notification

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.shadow.ShadowGuard
import doobie.implicits._
import doobie.util.transactor.Transactor

// The delivery relay (P2.6 / doc 34): routes each pending external notification through its NotificationChannel,
// then marks it sent/failed. Outbound, so it runs through ShadowGuard — in the shadow dual-run email/push/webhook
// are SUPPRESSED (recorded in shadow_action, not sent) and the row is marked sent (it would have gone). In-app
// notifications never reach here (they land 'sent' at fan-out — the row IS the in-app message).
final class NotificationDelivery[F[_]: Async](
    xa: Transactor[F],
    channel: NotificationChannel[F],
    shadow: ShadowGuard[F]
) {

  def deliverPending(limit: Int = 100): F[Int] =
    NotificationRepo.pendingForDelivery(limit).transact(xa).flatMap { rows =>
      rows
        .traverse_ {
          case (id, ch, endpoint, subject, body, payload) =>
            shadow
              .outbound(s"notify.$ch", endpoint.getOrElse(""), payload)(
                channel.deliver(ch, endpoint, subject, body, payload)
              )
              .flatMap {
                case None =>
                  NotificationRepo.markSent(List(id)).transact(xa).void // shadow-suppressed: audited + marked
                case Some(Right(_)) => NotificationRepo.markSent(List(id)).transact(xa).void
                case Some(Left(_))  => NotificationRepo.markFailed(id).transact(xa).void
              }
        }
        .as(rows.size)
    }
}
