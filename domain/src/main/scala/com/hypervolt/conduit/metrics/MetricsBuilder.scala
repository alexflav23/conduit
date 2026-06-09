package com.hypervolt.conduit.metrics

import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.ObservableLongGauge
import io.opentelemetry.api.metrics.ObservableLongMeasurement

// Lean port of Athena's MetricsBuilder (doc 19 §C.1). Names are `<service>_<postfix>` (`conduit_…` for the API,
// `conduit_consumer_…` for the consumer). Today we expose the operational GAUGES the alarms watch (§C.3); the
// counter/histogram builders match the house shape so per-DAO/HTTP timing (the Athena `metricsBuilder.time(...)`
// pattern) can be threaded in incrementally without re-deciding anything.
final class MetricsBuilder(val meter: Meter, serviceName: String) {

  private def name(postfix: String): String = s"${serviceName}_$postfix"

  // An async gauge — its callback runs on each Prometheus scrape (so it reports the live value).
  def gauge(postfix: String)(cb: ObservableLongMeasurement => Unit): ObservableLongGauge =
    meter.gaugeBuilder(name(postfix)).ofLongs().buildWithCallback(c => cb(c))

  def counter(postfix: String): LongCounter = meter.counterBuilder(name(postfix)).build()

  def histogramMillis(postfix: String) = meter.histogramBuilder(name(postfix)).build()
}
