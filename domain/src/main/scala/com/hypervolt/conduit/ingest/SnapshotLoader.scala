package com.hypervolt.conduit.ingest

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import scala.jdk.CollectionConverters._

// The git-snapshot ingest (doc 26 §3a): external history lives as NDJSON files committed to the repo
// (`ingest/<source>/<dataset>.ndjson`), and loading them is DETERMINISTIC and IDEMPOTENT — checkout → docker
// compose up → seeded, and a re-boot re-loads to the identical state (every handler upserts on a natural key).
// Scrapers (HubSpot, ghost-busters, SMMT car sales) are one-shot out-of-band tools that only PRODUCE files;
// Conduit takes no runtime dependency on any external system. New datasets = new handlers in the registry.
final class SnapshotLoader[F[_]: Async](xa: Transactor[F]) {

  // Load every dataset under the ingest root. A missing root is a clean no-op (prod seeds differently).
  def loadAll(root: Path): F[Int] =
    Async[F].blocking(Files.isDirectory(root)).flatMap {
      case false => 0.pure[F]
      case true =>
        Async[F]
          .blocking(
            Files
              .walk(root)
              .iterator()
              .asScala
              .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".ndjson"))
              .toList
              .sortBy(_.toString)
          )
          .flatMap(_.traverse(loadFile(root, _)).map(_.sum))
    }

  private def loadFile(root: Path, file: Path): F[Int] = {
    val rel     = root.relativize(file).toString               // e.g. exogenous/uk_car_sales.ndjson
    val source  = rel.split('/').headOption.getOrElse("other") // the dataset family
    val dataset = file.getFileName.toString.stripSuffix(".ndjson")
    Async[F].blocking(Files.readAllLines(file, StandardCharsets.UTF_8).asScala.toList.filter(_.nonEmpty)).flatMap {
      lines =>
        val rows = lines.flatMap(l => parse(l).toOption)
        SnapshotLoader.handlers.get(source) match {
          case None          => 0.pure[F] // unknown dataset families are skipped, never an error
          case Some(handler) => rows.traverse(handler(dataset, _)).map(_.sum).transact(xa)
        }
    }
  }
}

object SnapshotLoader {

  // dataset family → (dataset name, one parsed NDJSON row) → rows written (0 on conflict = idempotent re-load).
  private[ingest] val handlers: Map[String, (String, Json) => ConnectionIO[Int]] = Map(
    "exogenous" -> exogenous,
    "hubspot"   -> hubspot
  )

  // ingest/exogenous/<series_key>.ndjson — {"period_month":"2024-01-01","value":123456,"known_at":"2024-02-05T00:00:00Z"}
  // The censored regressor store (doc 26 §5): known_at is what lets a backtest see only what was knowable then.
  private def exogenous(seriesKey: String, row: Json): ConnectionIO[Int] = {
    val c = row.hcursor
    (
      c.get[String]("period_month").toOption.flatMap(s => scala.util.Try(LocalDate.parse(s)).toOption),
      c.get[BigDecimal]("value").toOption,
      c.get[String]("known_at").toOption.flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
    ).tupled match {
      case None => 0.pure[ConnectionIO]
      case Some((period, value, knownAt)) =>
        sql"""INSERT INTO exogenous_series (series_key, period_month, value, known_at)
              VALUES ($seriesKey, $period, $value, $knownAt)
              ON CONFLICT (series_key, period_month, known_at) DO NOTHING""".update.run
    }
  }

  // ingest/hubspot/deals_lifecycle.ndjson → deal_snapshot (the order-book substrate, doc 26 §4a).
  // deals_won.ndjson is the older won-only scrape — lifecycle supersedes it, so it is skipped here.
  private def hubspot(dataset: String, row: Json): ConnectionIO[Int] =
    if (dataset != "deals_lifecycle") 0.pure[ConnectionIO]
    else {
      val c = row.hcursor
      (
        c.get[String]("deal_id").toOption,
        c.get[String]("created").toOption.flatMap(s => scala.util.Try(LocalDate.parse(s)).toOption),
        c.get[String]("pipeline").toOption
      ).tupled match {
        case None => 0.pure[ConnectionIO]
        case Some((dealId, created, pipeline)) =>
          val closed   = c.get[String]("closed").toOption.flatMap(s => scala.util.Try(LocalDate.parse(s)).toOption)
          val won      = c.get[String]("won").toOption.contains("true")
          val isClosed = c.get[String]("is_closed").toOption.contains("true") || won
          val amount   = c.get[String]("amount").toOption.flatMap(s => scala.util.Try(BigDecimal(s)).toOption)
          val payment  = c.get[String]("payment").toOption
          sql"""INSERT INTO deal_snapshot (deal_id, pipeline, created_at, closed_at, is_won, is_closed, amount, payment_method)
                VALUES ($dealId, $pipeline, $created, $closed, $won, $isClosed,
                        ${amount.getOrElse(BigDecimal(0))}, $payment)
                ON CONFLICT (deal_id) DO UPDATE SET
                  pipeline = EXCLUDED.pipeline, created_at = EXCLUDED.created_at,
                  closed_at = EXCLUDED.closed_at, is_won = EXCLUDED.is_won,
                  is_closed = EXCLUDED.is_closed, amount = EXCLUDED.amount,
                  payment_method = EXCLUDED.payment_method""".update.run
      }
    }
}
