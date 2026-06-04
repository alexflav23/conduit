package com.hypervolt.conduit.forecast

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.util.UUID

object SkuMixRepo {

  // Resolve the applicable mix for a (channel, market): the most specific active mix wins — a channel+market
  // match, then channel, then market, then the global default. Returns its (variant, weight) lines.
  def resolve(channel: Option[UUID], market: Option[UUID]): ConnectionIO[List[(UUID, BigDecimal)]] =
    sql"""SELECT l.product_variant_id, l.weight
          FROM sku_mix m JOIN sku_mix_line l ON l.mix_id = m.id
          WHERE m.active
            AND (m.scope_channel_id IS NULL OR m.scope_channel_id = $channel)
            AND (m.scope_market_id IS NULL OR m.scope_market_id = $market)
            AND m.id = (
              SELECT m2.id FROM sku_mix m2 WHERE m2.active
                AND (m2.scope_channel_id IS NULL OR m2.scope_channel_id = $channel)
                AND (m2.scope_market_id IS NULL OR m2.scope_market_id = $market)
              ORDER BY (m2.scope_channel_id IS NOT NULL)::int + (m2.scope_market_id IS NOT NULL)::int DESC,
                       m2.created_at DESC
              LIMIT 1)"""
      .query[(UUID, BigDecimal)]
      .to[List]

  def createMix(
      name: String,
      channel: Option[UUID],
      market: Option[UUID],
      lines: List[(UUID, BigDecimal)]
  ): ConnectionIO[UUID] =
    for {
      id <-
        sql"INSERT INTO sku_mix (name, scope_channel_id, scope_market_id) VALUES ($name, $channel, $market) RETURNING id"
          .query[UUID]
          .unique
      _ <- lines.traverse_ {
        case (v, w) =>
          sql"INSERT INTO sku_mix_line (mix_id, product_variant_id, weight) VALUES ($id, $v, $w)".update.run.void
      }
    } yield id
}
