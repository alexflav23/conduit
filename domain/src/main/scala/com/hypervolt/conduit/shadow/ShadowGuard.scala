package com.hypervolt.conduit.shadow

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json

// The shadow-mode gate (doc 33 §5). Every outbound, business-affecting side-effect runs through `outbound`:
// in normal mode it executes and returns Some(result); in shadow mode it is SUPPRESSED — a shadow_action row is
// recorded (what we would have sent) and None is returned, the effect never running. The ledger / projections /
// reconciliations are unaffected — only the reach-outside-the-business calls are muted, so Conduit can run a
// full parallel set of books without touching Xero/HubSpot/Stripe/customers.
trait ShadowGuard[F[_]] {
  def shadow: Boolean
  def outbound[A](action: String, ref: String, detail: Json)(effect: F[A]): F[Option[A]]
}

object ShadowGuard {

  // The default everywhere outside the consumer wiring: never suppresses, no audit — behaves as if shadow were off.
  def disabled[F[_]: Applicative]: ShadowGuard[F] =
    new ShadowGuard[F] {
      val shadow: Boolean                                                                    = false
      def outbound[A](action: String, ref: String, detail: Json)(effect: F[A]): F[Option[A]] = effect.map(_.some)
    }

  // The active guard: suppress + audit when `shadow`, else run.
  def apply[F[_]: Async](xa: Transactor[F], shadowOn: Boolean): ShadowGuard[F] =
    if (!shadowOn) disabled[F]
    else
      new ShadowGuard[F] {
        val shadow: Boolean = true
        def outbound[A](action: String, ref: String, detail: Json)(effect: F[A]): F[Option[A]] =
          sql"INSERT INTO shadow_action (action, ref, detail) VALUES ($action, $ref, ${detail.noSpaces}::jsonb)".update.run
            .transact(xa)
            .as(none[A])
      }
}
