package com.hypervolt.conduit.api

import cats.effect.Async
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.MeterProvider
import java.util.concurrent.atomic.AtomicReference
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.metrics.opentelemetry.OpenTelemetryMetrics

// Tapir HTTP server options carrying the OpenTelemetry metrics interceptor (the hypervolt-backend standard —
// `DeviceApi` wires the same `OpenTelemetryMetrics.default(otel.getMeter("tapir"))` interceptor, doc 19 §C.1):
// per-endpoint request count / duration / active-request series under the `tapir` meter, exported on :9464
// alongside the operational gauges. hypervolt-backend reads the meter from its global OTel instance; we install it
// once at API start (`Main`, after the OTel SDK is built) into this holder rather than registering a JVM-global
// singleton (so multiple test suites can stand up exporters without the global-registration conflict). Until it is
// installed — and in route unit tests that never start the exporter — it is a no-op meter, so every route
// interprets and serves identically with HTTP metrics simply disabled.
object ApiMetrics {

  private val meterRef: AtomicReference[Meter] =
    new AtomicReference(MeterProvider.noop().get("tapir"))

  def install(meter: Meter): Unit = meterRef.set(meter)

  def serverOptions[F[_]: Async]: Http4sServerOptions[F] =
    Http4sServerOptions
      .customiseInterceptors[F]
      .metricsInterceptor(OpenTelemetryMetrics.default[F](meterRef.get()).metricsInterceptor())
      .options
}
