package com.hypervolt.conduit.gl

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.syntax._
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

// GL / AR / AP projection OFF THE LEDGER (doc 07 M13). The trial balance is read directly from the TigerBeetle
// immutable ledger — not a parallel set of books — so it reconciles by construction: Σ debits == Σ credits per
// currency. AR is per customer (AR:<party>), the P&L/asset accounts per entity. This is the read-model the GL
// and the M13b TB↔GL reconciliation sit on.
final class GlProjectionService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private def acc(key: String): BigInt         = TbIds.accountId(key)
  private def money(minor: BigInt): BigDecimal = (BigDecimal(minor) / 100).setScale(2, RoundingMode.HALF_UP)

  // The parties with an invoice on this entity's orders — their AR sub-ledger accounts.
  private def arParties(entity: UUID): ConnectionIO[List[(UUID, String)]] =
    sql"""SELECT DISTINCT o.bill_to_party_id, COALESCE(p.legal_name, p.display_name)
          FROM order_invoice i JOIN "order" o ON o.id = i.order_id JOIN party p ON p.id = o.bill_to_party_id
          WHERE o.entity_id = $entity ORDER BY 2"""
      .query[(UUID, String)]
      .to[List]

  // The VAT control is per (entity, jurisdiction); the trial balance lists one VAT line per jurisdiction the entity
  // owes in (recognised, plus its home). Year-1 that's a single VAT:GB line.
  private def vatJurisdictions(entity: UUID): ConnectionIO[List[String]] =
    sql"""SELECT DISTINCT jur FROM (
            SELECT vat_jurisdiction AS jur FROM revenue_recognition WHERE entity_id = $entity AND vat_jurisdiction IS NOT NULL
            UNION SELECT jurisdiction FROM entity WHERE id = $entity
          ) j WHERE jur IS NOT NULL ORDER BY jur"""
      .query[String]
      .to[List]

  def trialBalance(entity: UUID): F[Json] =
    (arParties(entity), vatJurisdictions(entity)).tupled.transact(xa).flatMap {
      case (parties, vatJurs) =>
        val vatAccounts = vatJurs.map(j => s"VAT:$j" -> acc(s"VAT:$entity:$j"))
        val entityAccounts = List(
          "REVENUE" -> acc(s"REVENUE:$entity"),
          "COGS"    -> acc(s"COGS:$entity"),
          "INV"     -> acc(s"INV:$entity")
        ) ::: vatAccounts
        val arAccounts = parties.map { case (pid, name) => s"AR:$name" -> acc(s"AR:$pid") }

        (entityAccounts ::: arAccounts)
          .traverse {
            case (label, id) =>
              ledger.balance(id).map(b => (label, b.debitsPosted, b.creditsPosted))
          }
          .map { rows =>
            val totalDr = rows.map(_._2).sum
            val totalCr = rows.map(_._3).sum
            Json.obj(
              "entity_id" -> entity.toString.asJson,
              "accounts" -> rows.map {
                case (label, dr, cr) =>
                  Json.obj(
                    "account" -> label.asJson,
                    "debits"  -> money(dr).asJson,
                    "credits" -> money(cr).asJson,
                    "balance" -> money(dr - cr).asJson
                  )
              }.asJson,
              "total_debits"  -> money(totalDr).asJson,
              "total_credits" -> money(totalCr).asJson,
              "balanced"      -> (totalDr == totalCr).asJson // proof: the ledger projection ties out
            )
          }
    }
}
