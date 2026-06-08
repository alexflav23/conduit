package com.hypervolt.conduit.document

import cats.Applicative
import cats.syntax.all._
import io.circe.Json
import io.circe.Printer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

final case class RenderedDoc(bytes: Array[Byte], contentSha256: String, pageCount: Int)

// The PDF engine is an ABSTRACTION, not a vendor (doc 17 §4.4). Any implementation must be DETERMINISTIC:
// the same (template body, render model) → byte-identical output (re-performability, doc 14 §5.1). The
// production implementation is `FopDocumentRenderer` (Apache FOP, XSL-FO → PDF, embedding the template
// font_stack incl. CJK + Thai); the `deterministic` text renderer below is a dependency-free stub that proves
// the same properties (determinism, sha-reproducibility) for fast unit/integration tests.
trait DocumentRenderer[F[_]] {
  def render(body: String, model: Json): F[RenderedDoc]
}

object DocumentRenderer {

  // Deterministic: canonicalise the model (sorted keys, no spaces) so the bytes — and thus the sha256 — are a
  // pure function of (body, model). Same inputs → same hash, every time.
  def deterministic[F[_]: Applicative]: DocumentRenderer[F] =
    (body: String, model: Json) => {
      val canonical = Printer.noSpaces.copy(sortKeys = true).print(model)
      val bytes     = (body + "\n---\n" + canonical).getBytes(StandardCharsets.UTF_8)
      RenderedDoc(bytes, sha256(bytes), 1).pure[F]
    }

  def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString
}
