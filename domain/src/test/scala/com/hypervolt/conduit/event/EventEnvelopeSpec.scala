package com.hypervolt.conduit.event

import com.hypervolt.conduit.pulsar.AvroPulsarSchema
import java.nio.charset.StandardCharsets
import weaver.SimpleIOSuite

// Proves the Avro spine round-trips without needing a live broker.
object EventEnvelopeSpec extends SimpleIOSuite {

  pureTest("an EventEnvelope avro-encodes and decodes back to an equal value") {
    val schema = AvroPulsarSchema.avroSchema[EventEnvelope]
    val env = EventEnvelope(
      event_id = "11111111-1111-1111-1111-111111111111",
      event_type = "order.placed",
      schema_version = 1,
      aggregate_type = "order",
      aggregate_id = "22222222-2222-2222-2222-222222222222",
      partition_key = "order-123",
      scope = Some("""{"entity_id":"e1"}"""),
      correlation_id = None,
      causation_id = None,
      actor = "system:relay",
      occurred_at = 1717430400000L,
      payload = """{"qty":3}""".getBytes(StandardCharsets.UTF_8)
    )
    val back = schema.decode(schema.encode(env), null)
    expect(back.event_id == env.event_id) and
      expect(back.event_type == env.event_type) and
      expect(back.partition_key == env.partition_key) and
      expect(back.scope == env.scope) and
      expect(back.correlation_id.isEmpty) and
      expect(new String(back.payload, StandardCharsets.UTF_8) == """{"qty":3}""")
  }

  pureTest("topic resolves per aggregate type with a conduit.<type> fallback") {
    expect(Topics.forAggregate("order") == "conduit.orders") and
      expect(Topics.forAggregate("serial") == "conduit.activations") and
      expect(Topics.forAggregate("widget") == "conduit.widget")
  }
}
