package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.warranty._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

object ActivationWarrantySuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val activatedAt = Instant.parse("2026-01-01T00:00:00Z")

  // entity + location + v3 variant + a serial on a batch with landed cost £100 (→ 5% provision = £5).
  private def seedSerial(xa: HikariTransactor[IO], generation: String): IO[(UUID, UUID, String)] = {
    val serial = s"SER-${UUID.randomUUID()}"
    (for {
      e   <- sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id".query[UUID].unique
      fam <- sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"}, 'Fam') RETURNING id".query[UUID].unique
      v   <- sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam, ${s"SKU-${UUID.randomUUID()}"}, $generation) RETURNING id".query[UUID].unique
      loc <- InventoryRepo.createLocation(Some(e), "W", "W")
      b   <- LotBatchRepo.create(NewBatch(s"B-${UUID.randomUUID()}", None, v, 1, BigDecimal("100.00"), BigDecimal("1.0"), "spot", None, BigDecimal("0"), BigDecimal("0"), "GBP"), LocalDate.parse("2025-12-01"))
      sid <- InventoryRepo.addSerial(serial, generation, v, Some(e), loc)
      _   <- LotBatchRepo.assignSerial(sid, b)
    } yield (e, sid, serial)).transact(xa)
  }

  test("first-write-wins, V2 ignored, idempotent redelivery; provision opens at the unit's batch cost") { xa =>
    val activation = new ActivationService[IO](xa)
    for {
      v3   <- seedSerial(xa, "v3")
      (_, sid, serial) = v3
      first  <- activation.onActivation(serial, UUID.randomUUID(), 1, activatedAt, None)
      again  <- activation.onActivation(serial, UUID.randomUUID(), 2, activatedAt, None) // redelivery / re-placement
      v2     <- seedSerial(xa, "v2")
      (_, _, v2serial) = v2
      ignored <- activation.onActivation(v2serial, UUID.randomUUID(), 1, activatedAt, None)
      status     <- sql"SELECT status FROM serial_unit WHERE id = $sid".query[String].unique.transact(xa)
      provisions <- sql"SELECT count(*) FROM warranty_provision WHERE serial_unit_id = $sid".query[Long].unique.transact(xa)
      estimated  <- sql"SELECT estimated_provision FROM warranty_provision WHERE serial_unit_id = $sid".query[BigDecimal].unique.transact(xa)
      v2acts     <- sql"SELECT count(*) FROM activation WHERE serial = $v2serial".query[Long].unique.transact(xa)
    } yield expect(first == ActivationOutcome.Activated) and
      expect(again == ActivationOutcome.AlreadyActivated) and
      expect(ignored == ActivationOutcome.IgnoredV2) and
      expect(status == "activated") and
      expect(provisions == 1L) and
      expect(estimated == BigDecimal("5.0000")) and
      expect(v2acts == 0L)
  }

  test("straight-line release advances exposure; consolidated exposure sums open provisions") { xa =>
    val activation = new ActivationService[IO](xa)
    val warranty   = new WarrantyService[IO](xa)
    for {
      seeded <- seedSerial(xa, "v3")
      (entity, sid, serial) = seeded
      _   <- activation.onActivation(serial, UUID.randomUUID(), 1, activatedAt, None) // start 2026-01-01, end 2028-01-01 (24mo)
      pid <- sql"SELECT id FROM warranty_provision WHERE serial_unit_id = $sid".query[UUID].unique.transact(xa)
      _   <- warranty.release(pid, LocalDate.parse("2027-01-01")) // 365 of 730 days -> half
      released   <- sql"SELECT released_to_date FROM warranty_provision WHERE id = $pid".query[BigDecimal].unique.transact(xa)
      exposure   <- warranty.consolidatedExposure(entity)
    } yield expect(released == BigDecimal("2.5000")) and expect(exposure == BigDecimal("2.5000"))
  }

  test("retroactive backfill rebuilds the provision register from activations") { xa =>
    val activation = new ActivationService[IO](xa)
    val warranty   = new WarrantyService[IO](xa)
    for {
      seeded <- seedSerial(xa, "v3")
      (entity, sid, serial) = seeded
      _      <- activation.onActivation(serial, UUID.randomUUID(), 1, activatedAt, None)
      _      <- warranty.releaseAllOpen(LocalDate.parse("2027-01-01"))
      before <- warranty.consolidatedExposure(entity)
      _      <- sql"DELETE FROM warranty_provision WHERE serial_unit_id = $sid".update.run.transact(xa)
      wiped  <- warranty.consolidatedExposure(entity)
      _      <- warranty.backfill(LocalDate.parse("2027-01-01"))
      after  <- warranty.consolidatedExposure(entity)
    } yield expect(wiped == BigDecimal(0)) and expect(after == before) and expect(after == BigDecimal("2.5000"))
  }
}
