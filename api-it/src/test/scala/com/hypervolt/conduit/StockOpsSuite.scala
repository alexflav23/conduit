package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.ledger._
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.stockops.StockOpsService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

object StockOpsSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val gbp     = Ledgers.forCurrency(Currency.GBP)
  private val maker   = UUID.randomUUID()
  private val checker = UUID.randomUUID()

  private def newVariant(xa: HikariTransactor[IO], serialised: Boolean): IO[(UUID, UUID, UUID)] =
    (for {
      e <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'F') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', $serialised) RETURNING id"
          .query[UUID]
          .unique
      loc <- InventoryRepo.createLocation(Some(e), "W", "W")
    } yield (e, v, loc)).transact(xa)

  private def batch(xa: HikariTransactor[IO], v: UUID, costUsd: String): IO[UUID] =
    LotBatchRepo
      .create(
        NewBatch(
          s"B-${UUID.randomUUID()}",
          None,
          v,
          1,
          BigDecimal(costUsd),
          BigDecimal("1.0"),
          "spot",
          None,
          BigDecimal("0"),
          BigDecimal("0"),
          "GBP"
        ),
        LocalDate.parse("2026-01-01")
      )
      .transact(xa)

  test(
    "write-off is maker-checker: self-approval rejected; a second approver posts the movement + ledger write-down at batch cost"
  ) {
    case (xa, client) =>
      val svc    = new StockOpsService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val serial = s"SER-${UUID.randomUUID()}"
      for {
        ids <- newVariant(xa, serialised = true)
        (e, v, loc) = ids
        b   <- batch(xa, v, "100.00") // landed £100
        _   <- InventoryRepo.receive(Some(e), v, loc, 1).transact(xa)
        sid <- InventoryRepo.addSerial(serial, "v3", v, Some(e), loc).transact(xa)
        _   <- LotBatchRepo.assignSerial(sid, b).transact(xa)
        _ <- ledger.createAccounts(
          List(
            LedgerAccount(svc.invAccount(e), gbp, LedgerAccountCode.Inv),
            LedgerAccount(svc.writeOffAccount(e), gbp, LedgerAccountCode.Inv)
          )
        )
        adjId  <- svc.requestAdjustment(e, loc, v, List(serial), 1, "write_off", "damaged", maker)
        selfNo <- svc.approveAdjustment(adjId, maker)
        ok     <- svc.approveAdjustment(adjId, checker)
        moves <-
          sql"SELECT count(*) FROM stock_movement WHERE type='write_off' AND ref_id=$adjId"
            .query[Long]
            .unique
            .transact(xa)
        serStatus <- sql"SELECT status FROM serial_unit WHERE serial_no=$serial".query[String].unique.transact(xa)
        invBal    <- ledger.balance(svc.invAccount(e))
      } yield expect(selfNo.isLeft) and expect(ok.isRight) and
        expect(moves == 1L) and expect(serStatus == "scrapped") and expect(invBal.creditsPosted == BigInt(10000))
  }

  test("cycle-count variance is maker-checker and posts a count_correction + ledger movement at batch cost") {
    case (xa, client) =>
      val svc    = new StockOpsService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      for {
        ids <- newVariant(xa, serialised = false)
        (e, v, loc) = ids
        _ <- batch(xa, v, "50.00") // landed £50 -> variantCost
        _ <- InventoryRepo.receive(Some(e), v, loc, 10).transact(xa)
        _ <- ledger.createAccounts(
          List(
            LedgerAccount(svc.invAccount(e), gbp, LedgerAccountCode.Inv),
            LedgerAccount(svc.writeOffAccount(e), gbp, LedgerAccountCode.Inv)
          )
        )
        countId <- svc.submitCount(e, loc, List((v, 8, 10)), maker) // counted 8 vs system 10 -> variance -2
        selfNo  <- svc.approveCount(countId, maker)
        ok      <- svc.approveCount(countId, checker)
        onHand <-
          sql"SELECT qty_on_hand FROM stock_item WHERE entity_id=$e AND product_variant_id=$v AND location_id=$loc"
            .query[Int]
            .unique
            .transact(xa)
        corr <-
          sql"SELECT count(*) FROM stock_movement WHERE type='count_correction' AND ref_id=$countId"
            .query[Long]
            .unique
            .transact(xa)
        invBal <- ledger.balance(svc.invAccount(e))
      } yield expect(selfNo.isLeft) and expect(ok.isRight) and
        expect(onHand == 8) and expect(corr == 1L) and expect(invBal.creditsPosted == BigInt(10000)) // 2 units * £50
  }

  test("a location transfer goes requested -> in_transit (maker-checker) -> received") {
    case (xa, client) =>
      val svc = new StockOpsService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
      for {
        ids <- newVariant(xa, serialised = false)
        (e, v, locA) = ids
        locB   <- InventoryRepo.createLocation(Some(e), "W2", "W2").transact(xa)
        _      <- InventoryRepo.receive(Some(e), v, locA, 5).transact(xa)
        tid    <- svc.requestTransfer(locA, locB, e, v, 3, maker)
        selfNo <- svc.approveTransfer(tid, maker)
        ok     <- svc.approveTransfer(tid, checker)
        aMid <-
          sql"SELECT qty_on_hand FROM stock_item WHERE product_variant_id=$v AND location_id=$locA"
            .query[Int]
            .unique
            .transact(xa)
        _ <- svc.receiveTransfer(tid)
        bFinal <-
          sql"SELECT qty_on_hand FROM stock_item WHERE product_variant_id=$v AND location_id=$locB"
            .query[Int]
            .unique
            .transact(xa)
        status <- sql"SELECT status FROM stock_transfer WHERE id=$tid".query[String].unique.transact(xa)
      } yield expect(selfNo.isLeft) and expect(ok.isRight) and
        expect(aMid == 2) and expect(bFinal == 3) and expect(status == "received")
  }
}
