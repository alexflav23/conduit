package com.hypervolt.conduit.document

import cats.effect.Async
import cats.effect.Ref
import cats.effect.Sync
import cats.syntax.all._
import java.net.URI
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest

// Where the finalised legal artefact lives (doc 17 §6). Object storage is a PORT: the WORM guarantee is the
// bucket's (S3 object-lock COMPLIANCE + versioning, provisioned by terraform); the app just puts once and never
// mutates. `put` returns the canonical `s3://bucket/key` URI that the document row records. LocalStack provides
// the same API in dev/CI, so this code path is identical everywhere.
trait DocumentStorage[F[_]] {
  def put(key: String, bytes: Array[Byte], contentType: String): F[String]
  def get(uri: String): F[Array[Byte]]
}

object DocumentStorage {

  // Dev/test stand-in: a Ref-backed store keyed by the same s3:// URI shape, so DocumentService is exercised
  // without S3. Rejecting a second write of the same key models the WORM contract the real bucket enforces.
  def inMemory[F[_]: Sync]: F[DocumentStorage[F]] =
    Ref.of[F, Map[String, Array[Byte]]](Map.empty).map { ref =>
      new DocumentStorage[F] {
        def put(key: String, bytes: Array[Byte], contentType: String): F[String] = {
          val uri = s"mem://$key"
          // WORM: keep the first write; a re-put of the (deterministic, identical) doc is a no-op.
          ref.update(m => if (m.contains(uri)) m else m.updated(uri, bytes)).as(uri)
        }
        def get(uri: String): F[Array[Byte]] =
          ref.get.flatMap(_.get(uri).liftTo[F](new NoSuchElementException(uri)))
      }
    }
}

// S3-backed WORM storage (doc 17 §6). One put per finalised document; versioning + object-lock on the bucket make
// it immutable. `endpoint` is set for LocalStack (path-style + static creds); unset uses the default chain + the
// instance role in AWS.
final class S3DocumentStorage[F[_]: Async](client: S3AsyncClient, bucket: String) extends DocumentStorage[F] {

  private val toBytes: AsyncResponseTransformer[GetObjectResponse, ResponseBytes[GetObjectResponse]] =
    AsyncResponseTransformer.toBytes()

  def put(key: String, bytes: Array[Byte], contentType: String): F[String] =
    Async[F]
      .fromCompletableFuture(
        Sync[F].delay(
          client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
            AsyncRequestBody.fromBytes(bytes)
          )
        )
      )
      .as(s"s3://$bucket/$key")

  def get(uri: String): F[Array[Byte]] = {
    val key = uri.stripPrefix(s"s3://$bucket/")
    Async[F]
      .fromCompletableFuture(
        Sync[F].delay(
          client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), toBytes)
        )
      )
      .map(_.asByteArray())
  }
}

object S3DocumentStorage {

  // Default client for AWS (instance-role creds, eu-west-1).
  def awsClient: S3AsyncClient =
    S3AsyncClient
      .builder()
      .region(Region.EU_WEST_1)
      .credentialsProvider(DefaultCredentialsProvider.builder().build())
      .build()

  // LocalStack / custom-endpoint client: path-style access + static creds (the bucket lives behind one endpoint).
  def endpointClient(endpoint: String, accessKey: String, secretKey: String): S3AsyncClient =
    S3AsyncClient
      .builder()
      .endpointOverride(URI.create(endpoint))
      .region(Region.EU_WEST_1)
      .forcePathStyle(true)
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
      .build()
}
