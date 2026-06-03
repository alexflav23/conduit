package com.hypervolt.conduit.pulsar

import com.sksamuel.avro4s._
import org.apache.pulsar.client.api.Schema
import org.apache.pulsar.client.api.schema.SchemaInfoProvider
import org.apache.pulsar.client.impl.schema.SchemaInfoImpl
import org.apache.pulsar.common.protocol.schema.BytesSchemaVersion
import org.apache.pulsar.common.schema.SchemaInfo
import org.apache.pulsar.common.schema.SchemaType

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

// Bridges avro4s case-class schemas to Pulsar's Schema[T] with schema-version caching (BACKWARD evolution).
// Copied verbatim from the house pattern (Athena/ghost-busters) so Conduit's wire format matches the estate.
object AvroPulsarSchema {
  def avroSchema[T: Manifest: SchemaFor: Encoder: Decoder]: Schema[T] = new AvroPulsarSchema[T]()
}

class AvroPulsarSchema[T: Manifest: SchemaFor: Encoder: Decoder](
    private var schemaInfoProvider: Option[SchemaInfoProvider] = None
) extends Schema[T] {

  private val generatedAvroSchema: org.apache.avro.Schema = AvroSchema[T]

  private val schemaCache = new ConcurrentHashMap[BytesSchemaVersion, org.apache.avro.Schema]()

  private def avroSchemaByVersion(schemaVersion: Option[Array[Byte]]): org.apache.avro.Schema =
    (schemaInfoProvider, schemaVersion) match {
      case (Some(provider), Some(version)) =>
        val bytesSchemaVersion = BytesSchemaVersion.of(version)
        schemaCache.get(bytesSchemaVersion) match {
          case null =>
            val pulsarSchemaInfo = provider.getSchemaByVersion(version).get
            val parser           = new org.apache.avro.Schema.Parser
            val schema           = parser.parse(pulsarSchemaInfo.getSchemaDefinition)
            schemaCache.put(bytesSchemaVersion, schema)
            schema
          case schema => schema
        }
      case _ => generatedAvroSchema
    }

  override def supportSchemaVersioning: Boolean = true

  override def setSchemaInfoProvider(provider: SchemaInfoProvider): Unit =
    this.schemaInfoProvider = Option(provider)

  override lazy val getSchemaInfo: SchemaInfo =
    SchemaInfoImpl
      .builder()
      .name(manifest[T].runtimeClass.getCanonicalName)
      .`type`(SchemaType.AVRO)
      .schema(generatedAvroSchema.toString.getBytes(StandardCharsets.UTF_8))
      .build()

  override def encode(t: T): Array[Byte] = {
    val baos = new ByteArrayOutputStream
    val aos  = AvroOutputStream.binary[T].to(baos).build()
    try aos.write(t)
    finally aos.close()
    baos.toByteArray
  }

  override def decode(bytes: Array[Byte], schemaVersionNullable: Array[Byte]): T = {
    val avroSchema = avroSchemaByVersion(Option(schemaVersionNullable))
    val ais        = AvroInputStream.binary[T].from(new ByteArrayInputStream(bytes)).build(avroSchema)
    try ais.iterator.next()
    finally ais.close()
  }

  override def clone(): Schema[T] = new AvroPulsarSchema(schemaInfoProvider)
}
