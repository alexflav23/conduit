package com.hypervolt.conduit.event

import com.sksamuel.avro4s.Decoder
import com.sksamuel.avro4s.Encoder
import com.sksamuel.avro4s.SchemaFor
import java.nio.charset.StandardCharsets

// The on-the-wire envelope (doc 03 §1). Avro field names are snake_case to match the registered schema.
// `payload` is the Avro/JSON-encoded typed payload; custom attributes ride inside it, never as schema fields.
final case class EventEnvelope(
    event_id: String,
    event_type: String,
    schema_version: Int,
    aggregate_type: String,
    aggregate_id: String,
    partition_key: String,
    scope: Option[String],
    correlation_id: Option[String],
    causation_id: Option[String],
    actor: String,
    occurred_at: Long,
    payload: Array[Byte]
)

object EventEnvelope {
  implicit val schemaFor: SchemaFor[EventEnvelope] = SchemaFor.gen[EventEnvelope]
  implicit val encoder: Encoder[EventEnvelope]     = Encoder.gen[EventEnvelope]
  implicit val decoder: Decoder[EventEnvelope]     = Decoder.gen[EventEnvelope]

  // The wire `actor` IS the persisted origin (the relay is just transport, not the cause).
  def fromOutbox(e: OutboxEvent, actor: String = ""): EventEnvelope = {
    val resolvedActor = if (actor.nonEmpty) actor else e.origin
    EventEnvelope(
      event_id = e.eventId.toString,
      event_type = e.eventType,
      schema_version = e.schemaVersion,
      aggregate_type = e.aggregateType,
      aggregate_id = e.aggregateId.toString,
      partition_key = e.partitionKey,
      scope = e.scope.map(_.noSpaces),
      correlation_id = e.correlationId.map(_.toString),
      causation_id = e.causationId.map(_.toString),
      actor = resolvedActor,
      occurred_at = e.occurredAt.toEpochMilli,
      payload = e.payload.noSpaces.getBytes(StandardCharsets.UTF_8)
    )
  }
}

// Topic per aggregate type (doc 01 §4 / doc 03 §1). Falls back to conduit.<aggregateType> for new aggregates.
object Topics {
  private val byAggregate: Map[String, String] = Map(
    "order"      -> "conduit.orders",
    "inventory"  -> "conduit.inventory",
    "serial"     -> "conduit.activations",
    "pricing"    -> "conduit.pricing",
    "price_rule" -> "conduit.pricing",
    "party"      -> "conduit.crm",
    "deal"       -> "conduit.crm",
    "commission" -> "conduit.commission",
    "ledger"     -> "conduit.ledger",
    "forecast"   -> "conduit.forecast",
    "po"         -> "conduit.purchasing"
  )

  def forAggregate(aggregateType: String): String =
    byAggregate.getOrElse(aggregateType, s"conduit.$aggregateType")
}
