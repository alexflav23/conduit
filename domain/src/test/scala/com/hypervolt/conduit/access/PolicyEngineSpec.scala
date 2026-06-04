package com.hypervolt.conduit.access

import java.util.UUID
import weaver.SimpleIOSuite

object PolicyEngineSpec extends SimpleIOSuite {

  private val uk        = UUID.randomUUID()
  private val ie        = UUID.randomUUID()
  private val wholesale = UUID.randomUUID()
  private val retail    = UUID.randomUUID()
  private val me        = UUID.randomUUID()

  private def grant(perms: List[Permission], markets: Set[UUID] = Set.empty, channels: Set[UUID] = Set.empty): Grant =
    Grant(perms, Set.empty, markets, channels, None)

  private val viewOrder =
    Permission("order", Action.View, None, Set(DataLayer.Volume, DataLayer.Commercial), Set.empty, Breadth.Scoped)

  // "UK wholesale only": scoped to market=UK, channel=wholesale.
  private val ukWholesale = Principal(me, Set.empty, List(grant(List(viewOrder), Set(uk), Set(wholesale))))

  private def order(market: UUID, channel: UUID): Target =
    Target(entityId = None, marketId = Some(market), channelId = Some(channel), ownerUserId = None)

  pureTest("UK-wholesale user is allowed a UK-wholesale row") {
    expect(PolicyEngine.authorize(ukWholesale, Action.View, "order", order(uk, wholesale)))
  }

  pureTest("UK-wholesale user is denied an IE-wholesale row (out of market scope)") {
    expect(!PolicyEngine.authorize(ukWholesale, Action.View, "order", order(ie, wholesale)))
  }

  pureTest("UK-wholesale user is denied a UK-retail row (out of channel scope)") {
    expect(!PolicyEngine.authorize(ukWholesale, Action.View, "order", order(uk, retail)))
  }

  pureTest("deny-by-default: no grant for an object means deny") {
    expect(!PolicyEngine.authorize(ukWholesale, Action.View, "price_rule", Target(None, None, None, None)))
  }

  pureTest("a sectioned permission does not grant general (no-section) access") {
    val interEntityOnly = Principal(
      me,
      Set.empty,
      List(
        grant(
          List(
            Permission(
              "price_rule",
              Action.View,
              Some("inter_entity"),
              Set(DataLayer.InterEntity),
              Set.empty,
              Breadth.All
            )
          )
        )
      )
    )
    expect(
      PolicyEngine.authorize(
        interEntityOnly,
        Action.View,
        "price_rule",
        Target(None, None, None, None),
        Some("inter_entity")
      )
    ) and
      expect(!PolicyEngine.authorize(interEntityOnly, Action.View, "price_rule", Target(None, None, None, None), None))
  }

  pureTest("own-breadth matches only the principal's own rows") {
    val ownOnly = Principal(
      me,
      Set.empty,
      List(Grant(List(viewOrder.copy(dataBreadth = Breadth.Own)), Set.empty, Set.empty, Set.empty, None))
    )
    expect(PolicyEngine.authorize(ownOnly, Action.View, "order", Target(None, None, None, Some(me)))) and
      expect(!PolicyEngine.authorize(ownOnly, Action.View, "order", Target(None, None, None, Some(UUID.randomUUID()))))
  }

  pureTest("revoking the grant denies on the next check") {
    val revoked = ukWholesale.copy(grants = Nil)
    expect(!PolicyEngine.authorize(revoked, Action.View, "order", order(uk, wholesale)))
  }
}
