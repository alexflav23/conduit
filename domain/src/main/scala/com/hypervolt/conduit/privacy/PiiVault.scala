package com.hypervolt.conduit.privacy

import cats.effect.Sync
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.util.UUID

// The PII vault (doc 19 §B.3): personal fields are stored encrypted under a per-subject DEK; reads decrypt on the
// fly; a crypto-shred destroys the DEK so reads thereafter return the `«erased»` tombstone — never a fabricated value
// or a null that looks like missing data. The non-personal skeleton lives in its own (un-encrypted) tables.
final class PiiVault[F[_]: Sync](xa: Transactor[F], crypto: CryptoShred) {

  // Store/replace one PII field for a subject. Creates the subject's DEK on first use; refuses a shredded subject.
  def put(subject: UUID, field: String, value: String): F[Either[String, Unit]] =
    dek(subject).flatMap {
      case None => "subject is erased — cannot store PII for a shredded subject".asLeft[Unit].pure[F]
      case Some(d) =>
        Sync[F]
          .delay(crypto.encrypt(d, value))
          .flatMap(ct => upsert(subject, field, ct).transact(xa).as(().asRight[String]))
    }

  // Read one PII field: the plaintext for an active subject, `«erased»` for a shredded one, None if never stored.
  def get(subject: UUID, field: String): F[Option[String]] =
    loadKey(subject).transact(xa).flatMap {
      case Some(("shredded", _)) => Option(CryptoShred.tombstone).pure[F]
      case Some(("active", Some(wrapped))) =>
        loadCipher(subject, field).transact(xa).flatMap {
          case None     => none[String].pure[F]
          case Some(ct) => Sync[F].delay(Option(crypto.decrypt(crypto.unwrap(wrapped), ct)))
        }
      case _ => none[String].pure[F]
    }

  // Crypto-shred: destroy the DEK. Irreversible; the ciphertext stays in place but is now undecryptable forever.
  def shred(subject: UUID, actor: UUID): F[Int] =
    sql"""UPDATE pii_key SET status='shredded', wrapped_dek=NULL, shredded_at=now(), shredded_by=$actor
          WHERE subject_id=$subject AND status<>'shredded'""".update.run.transact(xa)

  def isShredded(subject: UUID): F[Boolean] =
    sql"SELECT status='shredded' FROM pii_key WHERE subject_id=$subject"
      .query[Boolean]
      .option
      .transact(xa)
      .map(_.getOrElse(false))

  // The active DEK for a subject — unwrap the existing one, or mint+wrap+store a new one. None if shredded.
  private def dek(subject: UUID): F[Option[Array[Byte]]] =
    loadKey(subject).transact(xa).flatMap {
      case Some(("shredded", _))           => none[Array[Byte]].pure[F]
      case Some(("active", Some(wrapped))) => Sync[F].delay(crypto.unwrap(wrapped).some)
      case _ =>
        Sync[F]
          .delay {
            val d = crypto.newDek()
            (d, crypto.wrap(d))
          }
          .flatMap { case (d, w) => insertKey(subject, w).transact(xa).as(d.some) }
    }

  private def loadKey(subject: UUID): ConnectionIO[Option[(String, Option[String])]] =
    sql"SELECT status, wrapped_dek FROM pii_key WHERE subject_id=$subject".query[(String, Option[String])].option

  private def loadCipher(subject: UUID, field: String): ConnectionIO[Option[String]] =
    sql"SELECT ciphertext FROM pii_record WHERE subject_id=$subject AND field=$field".query[String].option

  private def insertKey(subject: UUID, wrapped: String): ConnectionIO[Int] =
    sql"INSERT INTO pii_key (subject_id, wrapped_dek, status) VALUES ($subject, $wrapped, 'active') ON CONFLICT (subject_id) DO NOTHING".update.run

  private def upsert(subject: UUID, field: String, ciphertext: String): ConnectionIO[Int] =
    sql"""INSERT INTO pii_record (subject_id, field, ciphertext) VALUES ($subject, $field, $ciphertext)
          ON CONFLICT (subject_id, field) DO UPDATE SET ciphertext=EXCLUDED.ciphertext, updated_at=now()""".update.run
}
