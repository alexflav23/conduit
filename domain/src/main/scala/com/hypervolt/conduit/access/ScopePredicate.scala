package com.hypervolt.conduit.access

import cats.data.NonEmptyList
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

// Query-time scope filtering (doc 05 §2): list reads are filtered at the data layer, not the UI. This
// builds a boolean SQL fragment from the principal's view-grants on `objectType`; no grant => `false`.
final case class ScopeColumns(entity: Fragment, market: Fragment, channel: Fragment, owner: Fragment)

object ScopeColumns {
  val default: ScopeColumns =
    ScopeColumns(fr"entity_id", fr"market_id", fr"channel_id", fr"owner_user_id")
}

object ScopePredicate {

  def forPrincipal(principal: Principal, objectType: String, cols: ScopeColumns = ScopeColumns.default): Fragment = {
    val frags = principal.grants.flatMap(g => grantFragment(g, principal, objectType, cols))
    NonEmptyList.fromList(frags).fold(fr"false")(nel => Fragments.or(nel))
  }

  private def grantFragment(
      grant: Grant,
      principal: Principal,
      objectType: String,
      cols: ScopeColumns
  ): Option[Fragment] = {
    val viewPerms = grant.permissions.filter(p => p.objectType == objectType && p.action == Action.View)
    if (viewPerms.isEmpty) None
    else {
      val breadth = grant.breadthOverride.getOrElse(mostRestrictive(viewPerms.map(_.dataBreadth)))
      breadth match {
        case Breadth.All => Some(fr"true")
        case Breadth.Own => Some(cols.owner ++ fr" = ${principal.userId}")
        case Breadth.Team =>
          Some(NonEmptyList.fromList(principal.teamMemberIds.toList).fold(fr"false")(Fragments.in(cols.owner, _)))
        case Breadth.Scoped =>
          val parts = List(
            NonEmptyList.fromList(grant.scopeEntities.toList).map(Fragments.in(cols.entity, _)),
            NonEmptyList.fromList(grant.scopeMarkets.toList).map(Fragments.in(cols.market, _)),
            NonEmptyList.fromList(grant.scopeChannels.toList).map(Fragments.in(cols.channel, _))
          ).flatten
          Some(NonEmptyList.fromList(parts).fold(fr"true")(nel => Fragments.and(nel)))
      }
    }
  }

  private def mostRestrictive(breadths: List[Breadth]): Breadth = {
    val rank = Map[Breadth, Int](Breadth.Own -> 0, Breadth.Team -> 1, Breadth.Scoped -> 2, Breadth.All -> 3)
    breadths.minByOption(rank.apply).getOrElse(Breadth.Scoped)
  }
}
