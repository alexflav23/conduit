package com.hypervolt.conduit.access

import io.circe.Json
import java.util.UUID
import weaver.SimpleIOSuite

object ProjectionSpec extends SimpleIOSuite {

  private val me = UUID.randomUUID()

  private def principalWithLayers(layers: Set[DataLayer]): Principal =
    Principal(
      me,
      Set.empty,
      List(
        Grant(
          List(Permission("price_rule", Action.View, None, layers, Set.empty, Breadth.All)),
          Set.empty,
          Set.empty,
          Set.empty,
          Set.empty,
          None
        )
      )
    )

  // A price_rule row carrying both customer (commercial) and inter-entity fields.
  private val priceRuleRow = Json.obj(
    "id"               -> Json.fromString("pr-1"),
    "authorised_price" -> Json.fromString("587.5000"),
    "max_discount_pct" -> Json.fromString("10.00"),
    "tp_method"        -> Json.fromString("cost_plus"),
    "tp_markup_pct"    -> Json.fromString("12.5000"),
    "from_entity_id"   -> Json.fromString("e-uk"),
    "to_entity_id"     -> Json.fromString("e-sg")
  )

  pureTest(
    "Deal Desk (volume+commercial, no inter_entity) cannot see inter-entity fields — they are absent from the payload"
  ) {
    val dealDesk = principalWithLayers(Set(DataLayer.Volume, DataLayer.Commercial))
    val out      = Projection.projectFor(dealDesk, "price_rule", priceRuleRow)
    val keys     = out.asObject.toList.flatMap(_.keys)
    expect(keys.contains("authorised_price")) and
      expect(keys.contains("max_discount_pct")) and
      expect(!keys.contains("tp_method")) and
      expect(!keys.contains("tp_markup_pct")) and
      expect(!keys.contains("from_entity_id")) and
      expect(keys.contains("id")) // unclassified stays
  }

  pureTest("a volume-only principal sees neither commercial nor inter-entity money") {
    val volumeOnly = principalWithLayers(Set(DataLayer.Volume))
    val keys       = Projection.projectFor(volumeOnly, "price_rule", priceRuleRow).asObject.toList.flatMap(_.keys)
    expect(!keys.contains("authorised_price")) and
      expect(!keys.contains("tp_markup_pct")) and
      expect(keys.contains("id"))
  }

  pureTest("a principal with the inter_entity layer sees the transfer-price fields") {
    val withInterEntity = principalWithLayers(Set(DataLayer.Commercial, DataLayer.InterEntity))
    val keys            = Projection.projectFor(withInterEntity, "price_rule", priceRuleRow).asObject.toList.flatMap(_.keys)
    expect(keys.contains("tp_markup_pct")) and expect(keys.contains("authorised_price"))
  }
}
