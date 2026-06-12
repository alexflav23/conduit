package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.close.LineageService
import com.hypervolt.conduit.demo.DemoBook
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.tigerbeetle.Client
import doobie.hikari.HikariTransactor
import weaver.IOSuite

// M-Proof P1 (spec doc 31 §1.5): the demo book is itself under test. Seeding one realistic contract year
// EXCLUSIVELY through the production write paths must leave a world where EVERY automated control passes —
// the CTO demo cannot arrive broken. Plus the append-only refusal and one full lineage walk.
object DemoBookSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  test("one seeded year: every automated control passes, the structure is complete, the margin is conserved") {
    case (xa, client) =>
      for {
        s <- DemoBook.seed(xa, TigerBeetleLedger.fromClient[IO](client))
        failing = s.controls.filter(_.violations != 0L)
        lineage <- new LineageService[IO](xa).forInvoice(s.sampleInvoiceId)
        sources   = lineage.flatMap(_.hcursor.downField("contractual_sources").downField("price_agreements").focus)
        transfers = lineage.flatMap(_.hcursor.downField("ledger_transfers").focus).flatMap(_.asArray)
      } yield expect(failing.isEmpty, "failing controls: " + failing.mkString(", ")) and
        expect(s.controls.size >= 15) and // the whole register ran, not a subset
        expect.same(s.recognized, 11) and
        expect.same(s.voids, 2) and
        expect.same(s.returns, 2) and
        expect.same(s.payments, 8) and
        expect(s.revenueExVat > BigDecimal(100000)) and
        expect(s.operatingCogs > BigDecimal(0)) and
        expect(s.principalMargin > BigDecimal(0)) and            // the LRD structure holds net of voids + unwinds
        expect(sources.exists(_.asArray.exists(_.nonEmpty))) and // the walk reaches the governed agreement
        expect(transfers.exists(_.nonEmpty))                     // and the immutable ledger
  }

  test("the book is append-only: a second seed refuses instead of duplicating") {
    case (xa, client) =>
      DemoBook
        .seed(xa, TigerBeetleLedger.fromClient[IO](client))
        .attempt
        .map(r => expect(r.swap.exists(_.getMessage.contains("already seeded"))))
  }
}
