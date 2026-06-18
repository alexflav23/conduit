package com.hypervolt.conduit.event

import com.sksamuel.avro4s.Decoder
import com.sksamuel.avro4s.Encoder
import com.sksamuel.avro4s.SchemaFor

// The inbound transport envelope (S1 shadow-mode inbox): the relay wraps each durably-landed `ingest_record`
// row and publishes it to conduit.inbound; the mapping consumer reads it and maps the raw payload through the
// shared SnapshotLoader handlers. This is transport only — durability lives in `ingest_record` (PG), Pulsar is
// the wire. `source`/`dataset`/`source_id` are the inbox key the consumer marks processed/failed against;
// `payload` is the raw source row exactly as landed (JSON bytes). snake_case to match the registered schema.
final case class InboundEnvelope(
    source: String,
    dataset: String,
    source_id: String,
    source_hash: String,
    payload: Array[Byte]
)

object InboundEnvelope {
  implicit val schemaFor: SchemaFor[InboundEnvelope] = SchemaFor.gen[InboundEnvelope]
  implicit val encoder: Encoder[InboundEnvelope]     = Encoder.gen[InboundEnvelope]
  implicit val decoder: Decoder[InboundEnvelope]     = Decoder.gen[InboundEnvelope]

  // One topic for all raw inbound (keyed by source so Pulsar preserves per-source ordering). Mapping fans back
  // out by source family inside the consumer, mirroring SnapshotLoader's handler registry.
  val topic: String = "conduit.inbound"
}
