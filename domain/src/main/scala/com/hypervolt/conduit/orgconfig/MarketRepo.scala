package com.hypervolt.conduit.orgconfig

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.util.UUID

// A market is the place of supply (doc 02 §A). Its jurisdiction resolves the seller-of-record entity (via
// `selling_entity`) and the tax engine's destination — so "which entity / which tax" follows from the market.
object MarketRepo {

  def jurisdiction(marketId: UUID): ConnectionIO[Option[String]] =
    sql"SELECT jurisdiction FROM market WHERE id = $marketId".query[String].option
}
