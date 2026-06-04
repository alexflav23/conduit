package com.hypervolt.conduit.access

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.util.UUID

// Assembles a Principal from the RBAC tables (doc 02 §B). Revocation is just the absence of an
// assignment on the next load — so the next request denies (doc 05 §6).
object AccessRepo {

  def loadPrincipal(keycloakId: String): ConnectionIO[Option[Principal]] =
    findUser(keycloakId).flatMap {
      case None => Option.empty[Principal].pure[ConnectionIO]
      case Some((userId, teamId)) =>
        for {
          members <- teamId.fold(List.empty[UUID].pure[ConnectionIO])(teamMembers)
          assigns <- assignments(userId)
          grants <- assigns.traverse {
            case (roleId, entities, markets, channels, breadthOverride) =>
              permissions(roleId).map(ps =>
                Grant(ps, entities.toSet, markets.toSet, channels.toSet, breadthOverride.flatMap(Breadth.fromName))
              )
          }
        } yield Some(Principal(userId, members.toSet, grants))
    }

  private def findUser(keycloakId: String): ConnectionIO[Option[(UUID, Option[UUID])]] =
    sql"SELECT id, team_id FROM app_user WHERE keycloak_id = $keycloakId".query[(UUID, Option[UUID])].option

  private def teamMembers(teamId: UUID): ConnectionIO[List[UUID]] =
    sql"SELECT member_user_ids FROM team WHERE id = $teamId".query[List[UUID]].option.map(_.getOrElse(Nil))

  private def assignments(
      userId: UUID
  ): ConnectionIO[List[(UUID, List[UUID], List[UUID], List[UUID], Option[String])]] =
    sql"""SELECT role_id, scope_entities, scope_markets, scope_channels, breadth_override
          FROM role_assignment WHERE user_id = $userId"""
      .query[(UUID, List[UUID], List[UUID], List[UUID], Option[String])]
      .to[List]

  private def permissions(roleId: UUID): ConnectionIO[List[Permission]] =
    sql"""SELECT object_type, action, section, viewable_layers, editable_layers, data_breadth
          FROM permission WHERE role_id = $roleId"""
      .query[(String, String, Option[String], List[String], List[String], String)]
      .to[List]
      .map(_.map {
        case (objectType, action, section, viewable, editable, breadth) =>
          Permission(
            objectType = objectType,
            action = Action.fromName(action).getOrElse(Action.View),
            section = section,
            viewableLayers = viewable.flatMap(DataLayer.fromCode).toSet,
            editableLayers = editable.flatMap(DataLayer.fromCode).toSet,
            dataBreadth = Breadth.fromName(breadth).getOrElse(Breadth.Scoped)
          )
      })
}
