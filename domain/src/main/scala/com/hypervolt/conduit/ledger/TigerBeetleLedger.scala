package com.hypervolt.conduit.ledger

import cats.effect.Sync
import com.tigerbeetle.AccountBatch
import com.tigerbeetle.Client
import com.tigerbeetle.CreateAccountResult
import com.tigerbeetle.CreateTransferResult
import com.tigerbeetle.IdBatch
import com.tigerbeetle.TransferBatch
import com.tigerbeetle.UInt128

final case class LedgerAccount(id: BigInt, ledger: Int, code: Int)

final case class LedgerTransfer(
    id: BigInt,
    debitAccountId: BigInt,
    creditAccountId: BigInt,
    amount: BigInt,
    ledger: Int,
    code: Int,
    flags: Int = 0,
    pendingId: Option[BigInt] = None
)

final case class AccountBalance(
    debitsPosted: BigInt,
    creditsPosted: BigInt,
    debitsPending: BigInt,
    creditsPending: BigInt
)

// The only writer to TigerBeetle (doc 01 §5). Posting is idempotent: a transfer whose id already exists
// returns `Exists`, which we treat as success — so redelivery never double-posts.
trait TigerBeetleLedger[F[_]] {
  def createAccounts(accounts: List[LedgerAccount]): F[Unit]
  def postTransfers(transfers: List[LedgerTransfer]): F[Unit]
  def balance(accountId: BigInt): F[AccountBalance]
}

object TigerBeetleLedger {

  final case class AccountCreateFailed(results: List[String])
      extends RuntimeException(s"ledger account create failed: ${results.mkString(", ")}")
  final case class LedgerPostFailed(results: List[String])
      extends RuntimeException(s"ledger post failed: ${results.mkString(", ")}")

  def fromClient[F[_]: Sync](client: Client): TigerBeetleLedger[F] =
    new TigerBeetleLedger[F] {

      private def bytes(b: BigInt): Array[Byte] = UInt128.asBytes(b.bigInteger)

      def createAccounts(accounts: List[LedgerAccount]): F[Unit] =
        Sync[F].blocking {
          val batch = new AccountBatch(accounts.size)
          accounts.foreach { a =>
            val _ = batch.add()
            batch.setId(bytes(a.id))
            batch.setLedger(a.ledger)
            batch.setCode(a.code)
            batch.setFlags(0)
          }
          val res      = client.createAccounts(batch)
          var failures = List.empty[String]
          while (res.next()) {
            val r = res.getResult
            if (r != CreateAccountResult.Exists) failures = r.toString :: failures
          }
          if (failures.nonEmpty) throw AccountCreateFailed(failures.reverse)
        }

      def postTransfers(transfers: List[LedgerTransfer]): F[Unit] =
        Sync[F].blocking {
          val batch = new TransferBatch(transfers.size)
          transfers.foreach { t =>
            val _ = batch.add()
            batch.setId(bytes(t.id))
            batch.setDebitAccountId(bytes(t.debitAccountId))
            batch.setCreditAccountId(bytes(t.creditAccountId))
            batch.setAmount(t.amount.bigInteger)
            batch.setLedger(t.ledger)
            batch.setCode(t.code)
            batch.setFlags(t.flags)
            t.pendingId.foreach(p => batch.setPendingId(bytes(p)))
          }
          val res      = client.createTransfers(batch)
          var failures = List.empty[String]
          while (res.next()) {
            val r = res.getResult
            if (r != CreateTransferResult.Exists) failures = r.toString :: failures
          }
          if (failures.nonEmpty) throw LedgerPostFailed(failures.reverse)
        }

      def balance(accountId: BigInt): F[AccountBalance] =
        Sync[F].blocking {
          val ids = new IdBatch(1)
          val _   = ids.add()
          ids.setId(bytes(accountId))
          val accounts = client.lookupAccounts(ids)
          if (accounts.next())
            AccountBalance(
              BigInt(accounts.getDebitsPosted),
              BigInt(accounts.getCreditsPosted),
              BigInt(accounts.getDebitsPending),
              BigInt(accounts.getCreditsPending)
            )
          else AccountBalance(0, 0, 0, 0)
        }
    }
}
