package com.hypervolt.conduit.access

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.util.UUID

// Persistence for the permission-builder API (doc 06 /admin/*).
object AdminRepo {

  def listRoles: ConnectionIO[List[(UUID, String, Boolean)]] =
    sql"SELECT id, name, is_preset FROM role ORDER BY name".query[(UUID, String, Boolean)].to[List]

  def createRole(name: String, description: Option[String]): ConnectionIO[UUID] =
    sql"INSERT INTO role (name, description) VALUES ($name, $description) RETURNING id".query[UUID].unique

  def addPermission(
      roleId: UUID,
      objectType: String,
      action: String,
      section: Option[String],
      viewableLayers: List[String],
      editableLayers: List[String],
      breadth: String
  ): ConnectionIO[Int] =
    sql"""INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
          VALUES ($roleId, $objectType, $action, $section, $viewableLayers, $editableLayers, $breadth)""".update.run

  def ensureUser(keycloakId: String, name: Option[String]): ConnectionIO[UUID] =
    sql"""INSERT INTO app_user (keycloak_id, name) VALUES ($keycloakId, $name)
          ON CONFLICT (keycloak_id) DO UPDATE SET name = COALESCE(EXCLUDED.name, app_user.name)
          RETURNING id""".query[UUID].unique

  def assign(
      userId: UUID,
      roleId: UUID,
      scopeEntities: List[UUID],
      scopeMarkets: List[UUID],
      scopeChannels: List[UUID],
      breadthOverride: Option[String]
  ): ConnectionIO[UUID] =
    sql"""INSERT INTO role_assignment (user_id, role_id, scope_entities, scope_markets, scope_channels, breadth_override)
          VALUES ($userId, $roleId, $scopeEntities, $scopeMarkets, $scopeChannels, $breadthOverride)
          RETURNING id""".query[UUID].unique
}
