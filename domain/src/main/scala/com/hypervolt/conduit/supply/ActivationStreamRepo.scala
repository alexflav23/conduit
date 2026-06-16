package com.hypervolt.conduit.supply

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.Instant

// Source for the live activation SSE stream (/activations Live feed): a recent backlog on connect, then anything
// newer than a moving cursor. Off the serial register — owner is Conduit's own serial→party attribution.
final case class ActivationEvent(serial: String, activatedAt: Instant, owner: Option[String])

object ActivationStreamRepo {

  private val base =
    fr"""SELECT s.serial_no, s.activated_at, p.display_name
         FROM serial_unit s JOIN product_variant v ON v.id = s.product_variant_id
         LEFT JOIN party p ON p.id = s.company_id
         WHERE v.product_class = 'charger' AND s.activated_at IS NOT NULL"""

  // Most-recent `limit`, returned oldest-first so the client prepends them in chronological order.
  def recentBacklog(limit: Int): ConnectionIO[List[ActivationEvent]] =
    (base ++ fr"ORDER BY s.activated_at DESC LIMIT ${limit.toLong}")
      .query[(String, Instant, Option[String])]
      .to[List]
      .map(_.reverse.map { case (sn, at, o) => ActivationEvent(sn, at, o) })

  // Anything newer than the cursor (the live tail), oldest-first.
  def since(cursor: Instant, limit: Int): ConnectionIO[List[ActivationEvent]] =
    (base ++ fr"AND s.activated_at > $cursor ORDER BY s.activated_at ASC LIMIT ${limit.toLong}")
      .query[(String, Instant, Option[String])]
      .to[List]
      .map(_.map { case (sn, at, o) => ActivationEvent(sn, at, o) })

  def latest: ConnectionIO[Option[Instant]] =
    sql"""SELECT max(s.activated_at) FROM serial_unit s JOIN product_variant v ON v.id = s.product_variant_id
          WHERE v.product_class = 'charger' AND s.activated_at IS NOT NULL"""
      .query[Option[Instant]]
      .unique
}
