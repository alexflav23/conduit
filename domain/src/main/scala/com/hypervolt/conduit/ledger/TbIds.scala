package com.hypervolt.conduit.ledger

import com.tigerbeetle.UInt128
import java.nio.charset.StandardCharsets
import java.util.UUID

// Deterministic 128-bit ids (doc 04 §Ledger). A transfer id is derived from the event_id (+ leg index),
// so redelivery of the same event produces the SAME transfer id — TigerBeetle then returns `exists` and
// the posting is a no-op. Account ids are derived from their stable string key (e.g. "AR:<party>").
object TbIds {
  private def u128(uuid: UUID): BigInt =
    BigInt(UInt128.asBigInteger(UInt128.asBytes(uuid)))

  def accountId(key: String): BigInt =
    u128(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)))

  def transferId(eventId: UUID, leg: Int): BigInt =
    u128(UUID.nameUUIDFromBytes(s"$eventId:$leg".getBytes(StandardCharsets.UTF_8)))
}
