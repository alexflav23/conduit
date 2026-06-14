package com.hypervolt.conduit.gl

import cats.effect.Async
import cats.syntax.all._
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.time.Instant
import java.util.UUID

// GL / AR / AP trial balance off the gl_entry MIRROR (doc 07 M13, Option B). gl_entry faithfully mirrors the
// TigerBeetle immutable ledger (reconciled by CTRL-GL-MIRROR), so the trial balance reads as plain SQL with no TB
// fan-out — and still ties by construction (Σ debits == Σ credits). `asOf` rolls balances to any UTC instant
// (period assignment is a re-projection, never baked into rows).
final class GlProjectionService[F[_]: Async](xa: Transactor[F]) {

  private def money(minor: BigDecimal): BigDecimal = GlMath.money(minor)

  def trialBalance(entity: UUID): F[Json] =
    GlEntryRepo.entityBalances(entity).transact(xa).map { rows =>
      val totalDr = rows.map(_._4).sum
      val totalCr = rows.map(_._5).sum
      Json.obj(
        "entity_id" -> entity.toString.asJson,
        "accounts" -> rows.map {
          case (key, _, ccy, dr, cr) =>
            Json.obj(
              "account"  -> key.asJson,
              "currency" -> ccy.asJson,
              "debits"   -> money(dr).asJson,
              "credits"  -> money(cr).asJson,
              "balance"  -> money(dr - cr).asJson
            )
        }.asJson,
        "total_debits"  -> money(totalDr).asJson,
        "total_credits" -> money(totalCr).asJson,
        "balanced"      -> GlMath.balanced(totalDr, totalCr).asJson // proof: the ledger projection ties out
      )
    }

  // The native-currency, exact, no-FX rollup as of a cutoff instant — the substrate the consolidation translates.
  def asOf(entity: UUID, at: Instant): F[Json] =
    GlEntryRepo.asOfBalances(entity, at).transact(xa).map { rows =>
      Json.obj(
        "entity_id" -> entity.toString.asJson,
        "as_of"     -> at.toString.asJson,
        "balances" -> rows.map {
          case (key, _, ccy, net) =>
            Json.obj("account" -> key.asJson, "currency" -> ccy.asJson, "balance" -> money(net).asJson)
        }.asJson
      )
    }
}
