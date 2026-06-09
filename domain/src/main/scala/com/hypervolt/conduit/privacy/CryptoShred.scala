package com.hypervolt.conduit.privacy

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Crypto-shred substrate (doc 19 §B.3). Per-subject envelope encryption: a random DEK encrypts the PII (AES-256-GCM),
// and the DEK is wrapped (encrypted) by a KMS-held KEK. Erasure = destroy the wrapped DEK → the ciphertext is
// permanently undecryptable (the KEK cannot reconstruct a destroyed DEK), while the financial skeleton stays intact.
// In prod the KEK comes from AWS KMS / Secrets Manager (doc 19 §B.1); `devKey` is a fixed local key for dev/test only.
final class CryptoShred(kek: Array[Byte]) {

  private val rnd     = new SecureRandom()
  private val tagBits = 128
  private val ivLen   = 12

  def newDek(): Array[Byte] = {
    val b = new Array[Byte](32)
    rnd.nextBytes(b)
    b
  }

  def wrap(dek: Array[Byte]): String                        = seal(kek, dek)
  def unwrap(wrapped: String): Array[Byte]                  = open(kek, wrapped)
  def encrypt(dek: Array[Byte], plaintext: String): String  = seal(dek, plaintext.getBytes(StandardCharsets.UTF_8))
  def decrypt(dek: Array[Byte], ciphertext: String): String = new String(open(dek, ciphertext), StandardCharsets.UTF_8)

  private def seal(key: Array[Byte], data: Array[Byte]): String = {
    val iv = new Array[Byte](ivLen)
    rnd.nextBytes(iv)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(tagBits, iv))
    Base64.getEncoder.encodeToString(iv ++ c.doFinal(data))
  }

  private def open(key: Array[Byte], b64: String): Array[Byte] = {
    val raw = Base64.getDecoder.decode(b64)
    val c   = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(tagBits, raw.take(ivLen)))
    c.doFinal(raw.drop(ivLen))
  }
}

object CryptoShred {
  // A FIXED 32-byte dev/test KEK — never used in a real environment (prod injects the KMS-wrapped KEK, doc 19 §B.1).
  val devKey: Array[Byte] = "conduit-dev-kek-not-for-prod!!32".getBytes(StandardCharsets.UTF_8).take(32)
  val tombstone: String   = "«erased»"
}
