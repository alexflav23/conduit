package com.hypervolt.conduit.logging

import ch.qos.logback.classic
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.MeterProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// Counts log events by level as an OpenTelemetry counter (copied from hypervolt-backend `OtelAppender`, doc 19
// §C.1/§C.3): `logs_count{level=ERROR}` is what the central WARN/ERROR-rate alerts fire on. Levels are seeded at 0
// so the series always exists (a missing metric would silently defeat the alert). A filtered variant excludes known
// benign noise so it never trips error alarms. Registered onto the logback root logger at process start.
object OtelAppender {

  private val LevelKey: AttributeKey[String] = AttributeKey.stringKey("level")

  def register(
      meterProvider: MeterProvider,
      name: String = "logs_count",
      predicate: ILoggingEvent => Boolean = _ => true
  ): Unit =
    register(meterProvider.get(classOf[OtelAppender].getName), name, predicate)

  def register(meter: Meter, name: String, predicate: ILoggingEvent => Boolean): Unit = {
    val context                    = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val rootLogger: classic.Logger = context.getLogger(Logger.ROOT_LOGGER_NAME)
    val appender                   = new OtelAppender(meter, name, predicate, LevelKey)
    appender.setContext(context)
    appender.start()
    rootLogger.addAppender(appender)
  }
}

class OtelAppender(meter: Meter, name: String, predicate: ILoggingEvent => Boolean, levelKey: AttributeKey[String])
    extends AppenderBase[ILoggingEvent] {

  private val logCounter = {
    val c = meter.counterBuilder(name).setDescription("Number of log messages by level").build()
    List(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR).foreach(level =>
      c.add(0, Attributes.of(levelKey, level.toString))
    )
    c
  }

  override def append(event: ILoggingEvent): Unit =
    if (predicate(event)) logCounter.add(1, Attributes.of(levelKey, event.getLevel.toString))
}
