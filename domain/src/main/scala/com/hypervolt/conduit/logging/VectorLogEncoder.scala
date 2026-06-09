package com.hypervolt.conduit.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.encoder.EncoderBase
import com.fasterxml.jackson.core.JsonEncoding
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import java.io.ByteArrayOutputStream

// Structured-JSON log encoder (copied from hypervolt-backend `VectorLogEncoder`, doc 19 §C.1): one JSON line per
// event to stdout, which the estate's Vector sidecar tails and ships to Loki/Grafana. Carries level/ts/logger,
// SLF4J key-value pairs (the `correlation_id` MDC lands here), the message, and a depth- and length-bounded
// stacktrace so a pathological exception can't blow the line budget. Selected in production via `logback.xml`
// (`HYPERVOLT_ENV=prod`); local dev keeps the human console pattern.
object VectorLogEncoder {
  val factory  = new JsonFactory()
  val tlStream = ThreadLocal.withInitial(() => new ByteArrayOutputStream)
}

class VectorLogEncoder extends EncoderBase[ILoggingEvent] {
  import VectorLogEncoder._

  private[this] val empty     = new Array[Byte](0)
  private[this] val newline   = '\n'.toInt
  private[this] val MaxLength = 16384
  private val MaxDepth        = 5

  private var includeThreadName = false

  def encode(event: ILoggingEvent): Array[Byte] = {
    val stream    = tlStream.get()
    val generator = factory.createGenerator(stream, JsonEncoding.UTF8)
    val msg       = event.getFormattedMessage()
    val timestamp = event.getTimeStamp()
    val name      = event.getLoggerName()
    generator.writeStartObject()
    generator.writeStringField("level", event.getLevel.toString)
    generator.writeStringField("ts", timestamp.toString)
    generator.writeStringField("name", name.toString)
    if (includeThreadName)
      generator.writeStringField("thread", event.getThreadName)
    generator.flush()

    if (event.getKeyValuePairs != null)
      event.getKeyValuePairs.forEach(pair => generator.writeObjectField(pair.key, pair.value))

    // slop to take care of minimal stacktrace field + json punctuation
    val requiredRemaining = stream.size + msg.size + 50
    if (requiredRemaining < MaxLength)
      generator.writeStringField("message", msg)
    else
      generator.writeStringField("message", msg.take(MaxLength - (stream.size + 50 + MaxDepth)) + "...")
    stacktrace("stacktrace", generator, stream, event.getThrowableProxy(), 0)
    generator.writeEndObject()

    generator.flush()
    stream.write(newline)
    stream.flush()
    val ret = stream.toByteArray()
    stream.reset()
    ret
  }

  def setThreadName(set: Boolean): Unit = includeThreadName = set

  def isThreadName: Boolean = includeThreadName

  def footerBytes(): Array[Byte] = empty
  def headerBytes(): Array[Byte] = empty

  final def stacktrace(
      field: String,
      generator: JsonGenerator,
      stream: ByteArrayOutputStream,
      proxy: IThrowableProxy,
      depth: Int
  ): Unit =
    if (proxy == null)
      generator.writeNullField(field)
    else {
      // to cover off for a syntactically complete message.
      val extraTokensSize = 256

      val klass = proxy.getClassName
      val message: Option[String] = Option(proxy.getMessage()).map { msg =>
        val addedSize = klass.size + msg.size + extraTokensSize
        if ((stream.size < (MaxLength - klass.size - extraTokensSize)) && (addedSize + stream.size > MaxLength)) {
          // reserve some space for stacks and limit to 2048 as this should be plenty for any message
          val shortened = math.min(MaxLength - stream.size - klass.size - (5 * extraTokensSize), 2048)
          if (shortened > 0) msg.substring(0, shortened)
          else "(elided)"
        } else msg
      }

      val fieldsSize = stream.size() + klass.size + message.map(_.size).getOrElse(0) + extraTokensSize
      if (fieldsSize < MaxLength) {
        generator.writeObjectFieldStart(field)
        message.foreach(m => generator.writeStringField("message", m))
        generator.writeStringField("class", klass)
        val els = {
          val iter = proxy.getStackTraceElementProxyArray().iterator.map(_.getSTEAsString())
          // If there is a cause leave a gap. 30 stack trace elements should be
          // enough to see what's what with the original exception
          if (proxy.getCause() == null) iter
          else iter.take(30) ++ Iterator("...")
        }
        var spaceRemaining = true
        generator.writeArrayFieldStart("backtrace")
        while (els.hasNext && spaceRemaining) {
          val element = els.next()
          generator.flush()
          // ~ 100 to cover off this + cause.
          spaceRemaining = stream.size + element.size + extraTokensSize < MaxLength
          if (spaceRemaining) generator.writeString(element)
        }
        generator.writeEndArray()
        if (depth < MaxDepth && spaceRemaining)
          stacktrace("cause", generator, stream, proxy.getCause(), depth + 1)
        else if (proxy.getCause() != null)
          generator.writeStringField(
            // Don't use `"cause"` here as it will break any attempt to decode it with JSON
            "causeSuppressed",
            "suppressed by VectorLogEncoder because depth too high: " + depth + ", max: " + MaxDepth
          )
        else generator.writeNullField("cause")

        generator.writeEndObject()
      } else
        // should not happen
        generator.writeStringField(
          field,
          "suppressed by VectorLogEncoder because field length too high: " + fieldsSize + ", max: " + MaxLength
        )
    }
}
