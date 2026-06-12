package com.hypervolt.conduit.scripting

import cats.effect.IO
import cats.effect.IOApp
import com.hypervolt.conduit.demo.DemoBook
import com.hypervolt.conduit.ledger.TigerBeetleClient
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import doobie.util.transactor.Transactor

// M-Proof P1 (spec doc 31): seed the demo book of record into the LOCAL compose stack and print the proof
// table. Seeding goes exclusively through the production write paths — see DemoBook. Reset = compose down -v.
//   sbt "scripting/runMain com.hypervolt.conduit.scripting.DemoSeed"
object DemoSeed extends IOApp.Simple {

  private def env(key: String, default: String) = sys.env.getOrElse(key, default)

  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    env("DEMO_PG_URL", "jdbc:postgresql://localhost:5532/conduit"),
    env("DEMO_PG_USER", "conduit"),
    env("DEMO_PG_PASSWORD", "conduit"),
    None
  )

  def run: IO[Unit] =
    TigerBeetleClient.make[IO](0, env("DEMO_TB_ADDRESSES", "localhost:3033")).use { client =>
      DemoBook.seed(xa, TigerBeetleLedger.fromClient[IO](client)).flatMap { s =>
        val widest = s.controls.map(_.code.length).maxOption.getOrElse(20)
        val table = s.controls
          .map(c =>
            s"  ${c.code.padTo(widest, ' ')}  ${c.result.toUpperCase.padTo(6, ' ')}  violations=${c.violations}"
          )
          .mkString("\n")
        val failing = s.controls.count(_.violations != 0L)
        IO.println(
          s"""
             |The demo book of record — seeded through the production write paths.
             |
             |  orders placed/recognized   ${s.orders} / ${s.recognized}
             |  voids / returns / payments ${s.voids} / ${s.returns} / ${s.payments}
             |  revenue (ex VAT)           £${s.revenueExVat}
             |  operating COGS (transfer)  £${s.operatingCogs}
             |  principal residual margin  £${s.principalMargin}
             |  sample lineage walk        GET /api/v1/finance/lineage?invoice_no=${s.sampleInvoiceNo}
             |
             |The proof table — every automated control, re-performed on the seeded book:
             |$table
             |
             |${if (failing == 0) "ALL CONTROLS PASS — the book holds because the system holds."
          else s"$failing CONTROL(S) FAILING — the system is telling you something; do not present this demo."}
             |""".stripMargin
        )
      }
    }
}
