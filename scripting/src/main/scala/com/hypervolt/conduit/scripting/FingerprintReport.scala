package com.hypervolt.conduit.scripting

import cats.effect.IO
import cats.effect.IOApp
import com.hypervolt.conduit.assurance.FingerprintService
import doobie.util.transactor.Transactor

// M-Assurance D (spec doc 29): record the reproducibility fingerprint of the compose DB's money tables at
// the current ingest SHA and print ingest/fingerprint.json. The refresher runs this per cycle and commits
// the file, so the digest is in git alongside the snapshot it fingerprints — making any future drift a
// visible diff. Re-running over the same snapshot must print the SAME digest (the law CTRL-REPRO polices).
//   INGEST_SHA=$(git -C ingest rev-parse HEAD) sbt "scripting/runMain ...FingerprintReport"
object FingerprintReport extends IOApp.Simple {

  private def env(k: String, d: String) = sys.env.getOrElse(k, d)

  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",
    env("DEMO_PG_URL", "jdbc:postgresql://localhost:5532/conduit"),
    env("DEMO_PG_USER", "conduit"),
    env("DEMO_PG_PASSWORD", "conduit"),
    None
  )

  def run: IO[Unit] = {
    val sha = env("INGEST_SHA", "unknown")
    new FingerprintService[IO](xa).record("ledger", sha).flatMap { digest =>
      IO.println(s"""{"scope":"ledger","ingest_sha":"$sha","digest":"$digest"}""")
    }
  }
}
