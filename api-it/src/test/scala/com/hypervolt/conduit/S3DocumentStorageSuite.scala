package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.document.S3DocumentStorage
import weaver.IOSuite

// M13-Docs.3 — the finalised document really lands in (LocalStack) S3 and reads back byte-for-byte. This proves
// the S3 code path the prod WORM bucket uses (doc 17 §6); the URI shape is the s3://bucket/key recorded on the row.
object S3DocumentStorageSuite extends IOSuite {

  override type Res = S3DocumentStorage[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestLocalStackS3.storage

  private val pdf = Array[Byte](0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x34) // "%PDF-1.4"

  test("put returns an s3:// URI and get reads the exact bytes back") { storage =>
    for {
      uri   <- storage.put("documents/round-trip.pdf", pdf, "application/pdf")
      bytes <- storage.get(uri)
    } yield expect(uri == "s3://" + TestLocalStackS3.bucket + "/documents/round-trip.pdf") and
      expect(bytes.sameElements(pdf))
  }

  test("a large binary payload survives the round-trip intact") { storage =>
    val big = Array.tabulate(200000)(i => (i % 256).toByte)
    for {
      uri   <- storage.put("documents/big.pdf", big, "application/pdf")
      bytes <- storage.get(uri)
    } yield expect(bytes.length == big.length) and expect(bytes.sameElements(big))
  }
}
