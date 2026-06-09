package com.hypervolt.conduit.metrics

import cats.effect.Async
import cats.effect.Resource
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer
import io.opentelemetry.instrumentation.runtimemetrics.java17.RuntimeMetrics
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.resources.{Resource => OtelResource}
import io.opentelemetry.semconv.ServiceAttributes.SERVICE_NAME

// House observability standard (copied from hypervolt-backend `OtelUtils`/`OtelSetup`, doc 19 §C.1): an OpenTelemetry
// SDK whose meter provider is read by a Prometheus exporter on `PROMETHEUS_PORT` (API 9464, consumer 9465). The
// meter provider carries the `service.name` resource attribute (so every series is tagged by service), and JVM
// runtime metrics (heap/GC/threads via the java17 instrumentation) are registered alongside. The estate's
// Vector/`hv-telemetry` sidecar scrapes `:PORT/metrics` — the scrape source is already registered in Conduit's
// Terraform (`conduit-api`/`conduit-consumer`) — and ships to Mimir; Grafana + alerting are central. Metrics-only,
// like the rest of the estate (no distributed tracing anywhere in hypervolt-backend or Athena; request correlation
// is via the log MDC `correlation_id`). We build a non-global SDK (not `buildAndRegisterGlobal`) so multiple suites
// can stand it up in one JVM without the global-singleton conflict.
object GlobalMetrics {

  def buildResource[F[_]: Async](
      serviceName: String = "conduit",
      defaultPort: Int = 9464
  ): Resource[F, OpenTelemetrySdk] = {
    val port = sys.env.get("PROMETHEUS_PORT").map(_.toInt).getOrElse(defaultPort)
    val resource =
      OtelResource.getDefault.merge(OtelResource.builder().put(SERVICE_NAME, serviceName).build())
    for {
      prometheus <- Resource.fromAutoCloseable(Async[F].delay(PrometheusHttpServer.builder().setPort(port).build()))
      otel <- Resource.fromAutoCloseable(
        Async[F].delay(
          OpenTelemetrySdk
            .builder()
            .setMeterProvider(
              SdkMeterProvider.builder().setResource(resource).registerMetricReader(prometheus).build()
            )
            .build()
        )
      )
      _ <- Resource.fromAutoCloseable(Async[F].delay(RuntimeMetrics.builder(otel).disableAllFeatures().build()))
    } yield otel
  }
}
