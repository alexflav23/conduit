package com.hypervolt.conduit.notification

import cats.Applicative
import cats.syntax.all._
import io.circe.Json

// The outbound delivery seam (P2.6 / doc 34): a channel turns a notification into an actual send (email via SES,
// push via FCM, webhook via HTTP). Behind a seam so the relay is testable without a provider; the live impls
// (SesEmailChannel / FcmPushChannel / WebhookChannel) land when their creds exist. `logging` is the pre-creds
// stand-in — it succeeds without an external call so the in-app + pipeline path is observable end to end.
trait NotificationChannel[F[_]] {
  def deliver(
      channel: String,
      endpoint: Option[String],
      subject: String,
      body: String,
      payload: Json
  ): F[Either[String, Unit]]
}

object NotificationChannel {
  def logging[F[_]: Applicative]: NotificationChannel[F] =
    (_: String, _: Option[String], _: String, _: String, _: Json) => Either.right[String, Unit](()).pure[F]
}
