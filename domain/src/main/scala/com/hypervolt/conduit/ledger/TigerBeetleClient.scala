package com.hypervolt.conduit.ledger

import cats.effect.Resource
import cats.effect.Sync
import com.tigerbeetle.Client
import com.tigerbeetle.UInt128
import java.math.BigInteger

// Builds the TigerBeetle client (doc 01 §5). cluster 0; addresses are a comma-separated host:port list. The
// client requires numeric ids, so the cluster id is the u128 of the configured cluster number.
object TigerBeetleClient {
  def make[F[_]: Sync](cluster: Long, addresses: String): Resource[F, Client] =
    Resource.fromAutoCloseable(
      Sync[F].delay(new Client(UInt128.asBytes(BigInteger.valueOf(cluster)), addresses.split(",").map(_.trim)))
    )
}
