package com.hypervolt.conduit.db

import com.hypervolt.conduit.orgconfig.EntityType
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.util.UUID

// Minimal repo used to demonstrate the atomic business-write + outbox commit in M1. entity_type is the closed
// EntityType enum (DB-enforced by a CHECK constraint), never free text.
object EntityRepo {

  def insert(
      name: String,
      jurisdiction: String,
      functionalCurrency: String,
      entityType: EntityType
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type)
          VALUES ($name, $jurisdiction, $functionalCurrency, ${entityType.value})
          RETURNING id""".query[UUID].unique
}
