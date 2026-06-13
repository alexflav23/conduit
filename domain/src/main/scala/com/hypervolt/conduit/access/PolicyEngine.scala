package com.hypervolt.conduit.access

// Server-side authorisation (doc 05 §1). Deny-by-default: a request is allowed iff some grant holds a
// permission for (objectType, action[, section]) whose scope matches the target.
object PolicyEngine {

  def authorize(
      principal: Principal,
      action: Action,
      objectType: String,
      target: Target,
      section: Option[String] = None
  ): Boolean =
    principal.grants.exists { grant =>
      grant.permissions
        .filter(p =>
          p.objectType == objectType &&
            p.action == action &&
            (p.section.isEmpty || p.section == section)
        )
        .exists(p => scopeMatches(grant, p, principal, target))
    }

  // Does the principal hold ANY grant of this permission (ignoring scope)? Used to gate scope-filtered lists,
  // where the rows are then narrowed by the scope predicate / breadth rather than a single target.
  def hasPermission(principal: Principal, action: Action, objectType: String): Boolean =
    principal.grants.exists(_.permissions.exists(p => p.objectType == objectType && p.action == action))

  def scopeMatches(grant: Grant, permission: Permission, principal: Principal, target: Target): Boolean = {
    val breadth = grant.breadthOverride.getOrElse(permission.dataBreadth)
    breadth match {
      case Breadth.All    => true
      case Breadth.Own    => target.ownerUserId.contains(principal.userId)
      case Breadth.Team   => target.ownerUserId.exists(principal.teamMemberIds.contains)
      case Breadth.Scoped =>
        // each axis ANDs; an empty axis is unconstrained (doc 05 §2). "UK Wholesale, energy" =
        // markets{UK} ∧ channels{wholesale} ∧ sectors{energy}.
        (grant.scopeEntities.isEmpty || target.entityId.exists(grant.scopeEntities.contains)) &&
          (grant.scopeMarkets.isEmpty || target.marketId.exists(grant.scopeMarkets.contains)) &&
          (grant.scopeChannels.isEmpty || target.channelId.exists(grant.scopeChannels.contains)) &&
          (grant.scopeSectors.isEmpty || target.sector.exists(grant.scopeSectors.contains))
    }
  }
}
