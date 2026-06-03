package com.hypervolt.conduit.db

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.util.UUID

// Minimal repo used to demonstrate the atomic business-write + outbox commit in M1.
object EntityRepo {

  def insert(
      name: String,
      jurisdiction: String,
      functionalCurrency: String,
      entityType: String
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type)
          VALUES ($name, $jurisdiction, $functionalCurrency, $entityType)
          RETURNING id""".query[UUID].unique
}
