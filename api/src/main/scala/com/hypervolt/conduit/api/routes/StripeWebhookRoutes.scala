package com.hypervolt.conduit.api.routes

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.payment.SignatureValidity
import com.hypervolt.conduit.payment.SignatureVerifier
import com.hypervolt.conduit.payment.StripeInboundRepo
import com.hypervolt.conduit.payment.StripeWebhook
import doobie.implicits._
import doobie.util.transactor.Transactor
import org.http4s.HttpRoutes
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.server.http4s.Http4sServerInterpreter

// The PUBLIC Stripe webhook (doc 13 §payments). Verifies the signature (when a secret is configured), then
// records the raw event idempotently on Stripe's event id and returns 200 — Stripe is satisfied it was received.
// It deliberately does NOT settle the ledger here (no TigerBeetle in the API): a consumer-side drain processes the
// recorded rows. An unverified or malformed payload never gets recorded. A duplicate redelivery is a 200 no-op.
final class StripeWebhookRoutes[F[_]: Async](xa: Transactor[F], verifier: Option[SignatureVerifier]) {

  private val endpointDef =
    endpoint.post
      .in("api" / "v1" / "stripe" / "webhook")
      .in(stringBody)
      .in(header[Option[String]]("Stripe-Signature"))
      .out(stringBody)
      .errorOut(statusCode.and(stringBody))

  private def logic(body: String, sig: Option[String]): F[Either[(StatusCode, String), String]] =
    verifier.map(_.verify(body, sig.getOrElse(""))).getOrElse(SignatureValidity.Valid) match {
      case SignatureValidity.Invalid(reason) =>
        (StatusCode.BadRequest, s"signature: $reason").asLeft[String].pure[F]
      case SignatureValidity.Valid =>
        StripeWebhook.envelope(body) match {
          case Left(err) => (StatusCode.BadRequest, err).asLeft[String].pure[F]
          case Right((id, tpe, json)) =>
            StripeInboundRepo.record(id, tpe, json).transact(xa).as("received".asRight[(StatusCode, String)])
        }
    }

  val routes: HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(endpointDef.serverLogic(t => logic(t._1, t._2)))
}
