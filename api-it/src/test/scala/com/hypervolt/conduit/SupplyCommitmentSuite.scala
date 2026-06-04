package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.supply.SupplyCommitmentService
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import weaver.IOSuite

// M11-H — the contract manufacturer's firm-commitment window. Forward demand commits into firm POs per SKU per
// week, gated by the time fence: frozen = no change, flex = ± tolerance, free = unconstrained; an escalated
// force overrides (liability-bearing). doc 12 buy-side; forecasting guide §1/§4.
object SupplyCommitmentSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val asOf = LocalDate.of(2026, 6, 1)

  private def supplier(xa: HikariTransactor[IO]): IO[UUID] =
    sql"INSERT INTO supplier (name, billing_currency) VALUES ('Volex','USD') RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def variant(xa: HikariTransactor[IO]): IO[UUID] =
    (for {
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'H3') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id"
          .query[UUID]
          .unique
    } yield v).transact(xa)

  test("the firm-commitment window gates changes: free establishes, flex bounds ±20%, frozen blocks; force overrides") {
    xa =>
      val svc = new SupplyCommitmentService[IO](xa)
      for {
        sup <- supplier(xa)
        v   <- variant(xa)
        freeTarget   = asOf.plusDays(210) // free window
        flexTarget   = asOf.plusDays(90)  // flex window
        frozenTarget = asOf.plusDays(14)  // frozen window

        // FREE: establish a 100-unit firm PO from zero
        free <- svc.commit(sup, v, freeTarget, 100, asOf, force = false)
        // FLEX: establish 100, then a +30% move (130) is rejected, a +15% move (115) is admitted
        _        <- svc.commit(sup, v, flexTarget, 100, asOf, force = false)
        flexOver <- svc.commit(sup, v, flexTarget, 130, asOf, force = false)
        flexOk   <- svc.commit(sup, v, flexTarget, 115, asOf, force = false)
        // FROZEN: seed 100 (as if it rolled into the frozen window), then any change is blocked unless forced
        _ <-
          sql"INSERT INTO supply_commitment (supplier_id, product_variant_id, target_date, qty, zone) VALUES ($sup,$v,$frozenTarget,100,'flex')".update.run
            .transact(xa)
        frozen      <- svc.commit(sup, v, frozenTarget, 110, asOf, force = false)
        frozenForce <- svc.commit(sup, v, frozenTarget, 110, asOf, force = true)

        // assess (the real-time headroom): a jump to 200 from the now-committed 115 exceeds the ±20% band
        assess <- svc.assess(sup, v, flexTarget, asOf, 200)
        events <-
          sql"SELECT count(*) FROM outbox_event WHERE event_type='supply.commitment.placed' AND aggregate_id=$sup"
            .query[Long]
            .unique
            .transact(xa)
        finalFlex <-
          sql"SELECT qty, zone FROM supply_commitment WHERE supplier_id=$sup AND product_variant_id=$v AND target_date=$flexTarget"
            .query[(Int, String)]
            .unique
            .transact(xa)
      } yield expect(free.map(_.zone) == Right("free")) and
        expect(flexOver == Left("exceeds_flex_tolerance")) and
        expect(flexOk.map(_.admissible) == Right(true)) and expect(finalFlex == ((115, "flex"))) and
        expect(frozen == Left("frozen_window")) and expect(frozenForce.isRight) and
        expect(assess.zone == "flex") and expect(!assess.admissible) and
        expect(events >= 4L)
  }
}
