package com.hypervolt.conduit.pulsar

import cats.effect.Resource
import cats.effect.Sync
import org.apache.pulsar.client.api.PulsarClient

object PulsarUtils {

  def makeClient[F[_]: Sync](serviceUrl: String): Resource[F, PulsarClient] =
    Resource.fromAutoCloseable(Sync[F].delay(PulsarClient.builder.serviceUrl(serviceUrl).build))
}
