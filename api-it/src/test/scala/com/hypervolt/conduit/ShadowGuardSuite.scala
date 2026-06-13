package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import com.hypervolt.conduit.shadow.ShadowGuard
import doobie.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.util.UUID
import weaver.IOSuite

// M-Ingest shadow mode (spec doc 33 §5): the gate every outbound effector runs through. Shadow ON suppresses
// the effect (it never runs) and records a shadow_action; shadow OFF runs it and audits nothing. This is what
// lets Conduit keep a full parallel set of books without touching Xero/HubSpot/Stripe/customers.
object ShadowGuardSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  test("shadow ON: the effect is suppressed (returns None, never runs) and a shadow_action is recorded") { xa =>
    val guard = ShadowGuard[IO](xa, shadowOn = true)
    val ref   = s"INV-${UUID.randomUUID()}"
    for {
      ran <- Ref.of[IO, Int](0)
      out <- guard.outbound("xero.invoice.create", ref, Json.obj("invoice_no" -> io.circe.Json.fromString(ref)))(
        ran.update(_ + 1).as("external-123")
      )
      didRun <- ran.get
      rows <-
        sql"SELECT count(*) FROM shadow_action WHERE action='xero.invoice.create' AND ref=$ref"
          .query[Long]
          .unique
          .transact(xa)
    } yield expect(guard.shadow) and expect(out.isEmpty) and expect(didRun == 0) and expect(rows == 1L)
  }

  test("shadow OFF: the effect runs (returns Some) and nothing is audited") { xa =>
    val guard = ShadowGuard[IO](xa, shadowOn = false)
    val ref   = s"INV-${UUID.randomUUID()}"
    for {
      ran    <- Ref.of[IO, Int](0)
      out    <- guard.outbound("xero.invoice.create", ref, Json.obj())(ran.update(_ + 1).as("external-123"))
      didRun <- ran.get
      rows   <- sql"SELECT count(*) FROM shadow_action WHERE ref=$ref".query[Long].unique.transact(xa)
    } yield expect(!guard.shadow) and expect(out.contains("external-123")) and expect(didRun == 1) and expect(
      rows == 0L
    )
  }
}
