package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.document.S3DocumentStorage
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.s3.model.CreateBucketRequest

// A LocalStack S3 in a container (the house dev/CI stand-in for S3). Creates the records bucket and hands back a
// real S3-backed DocumentStorage pointed at the container endpoint — so the S3 code path is exercised end-to-end
// without AWS. The prod bucket (object-lock + versioning) is provisioned by terraform/conduit-records.
object TestLocalStackS3 {

  val bucket = "conduit-records-test"

  def storage: Resource[IO, S3DocumentStorage[IO]] =
    Resource
      .make(IO {
        val c = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.4"))
          .withServices(Service.S3)
        c.start()
        c
      })(c => IO(c.stop()))
      .evalMap { c =>
        IO {
          val client = S3DocumentStorage.endpointClient(
            c.getEndpointOverride(Service.S3).toString,
            c.getAccessKey,
            c.getSecretKey
          )
          client.createBucket(CreateBucketRequest.builder().bucket(bucket).build()).get()
          new S3DocumentStorage[IO](client, bucket)
        }
      }
}
