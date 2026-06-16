package com.hypervolt.conduit.orgconfig

// The org entity type — a closed enum, not free text (also DB-enforced by a CHECK constraint, V1_0_86). Hypervolt
// has OPERATING entities (sell/operate in a market) and a PROCUREMENT entity (buys from the contract manufacturer).
// Introducing a new type means adding a case here AND extending the CHECK constraint — the two stay in lockstep.
sealed abstract class EntityType(val value: String)
object EntityType {
  case object Operating   extends EntityType("operating")
  case object Procurement extends EntityType("procurement")

  val all: List[EntityType] = List(Operating, Procurement)

  def fromString(s: String): Either[String, EntityType] =
    all.find(_.value == s).toRight(s"unknown entity_type: '$s' (expected one of ${all.map(_.value).mkString(", ")})")
}
