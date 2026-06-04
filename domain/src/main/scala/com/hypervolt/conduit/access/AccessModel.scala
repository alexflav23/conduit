package com.hypervolt.conduit.access

import java.util.UUID

sealed abstract class Action(val name: String)
object Action {
  case object View    extends Action("view")
  case object Edit    extends Action("edit")
  case object Create  extends Action("create")
  case object Delete  extends Action("delete")
  case object Approve extends Action("approve")
  case object Export  extends Action("export")

  val all: List[Action]                   = List(View, Edit, Create, Delete, Approve, Export)
  def fromName(n: String): Option[Action] = all.find(_.name == n)
}

// How wide a grant reaches (doc 05 §1).
sealed abstract class Breadth(val name: String)
object Breadth {
  case object All    extends Breadth("all")
  case object Team   extends Breadth("team")
  case object Own    extends Breadth("own")
  case object Scoped extends Breadth("scoped")

  val all: List[Breadth]                   = List(All, Team, Own, Scoped)
  def fromName(n: String): Option[Breadth] = all.find(_.name == n)
}

// One permission row: object × action × optional section (field-group) × layers × breadth.
final case class Permission(
    objectType: String,
    action: Action,
    section: Option[String],
    viewableLayers: Set[DataLayer],
    editableLayers: Set[DataLayer],
    dataBreadth: Breadth
)

// A scoped assignment of a role's permissions to a principal (doc 02 §B role_assignment).
final case class Grant(
    permissions: List[Permission],
    scopeEntities: Set[UUID],
    scopeMarkets: Set[UUID],
    scopeChannels: Set[UUID],
    breadthOverride: Option[Breadth]
)

// The authenticated principal and all of their grants (assembled from Keycloak identity + DB).
final case class Principal(userId: UUID, teamMemberIds: Set[UUID], grants: List[Grant])

// The scoping axes of the object being authorised/served.
final case class Target(
    entityId: Option[UUID],
    marketId: Option[UUID],
    channelId: Option[UUID],
    ownerUserId: Option[UUID]
)
