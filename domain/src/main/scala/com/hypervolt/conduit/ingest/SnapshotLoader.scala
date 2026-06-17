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
import java.util.UUID
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
          // Isolate each file: its rows still load atomically (one tx), but a failure in one dataset can never
          // abort the others or crash the API boot — the import engine degrades, it does not take the app down.
          .flatMap(_.traverse(f => loadFile(root, f).attempt.map(_.fold(_ => 0, identity))).map(_.sum))
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
    "exogenous"    -> exogenous,
    "hubspot"      -> hubspot,
    "mrpeasy"      -> mrpeasy,
    "ghostbusters" -> ghostbusters,
    "h6q"          -> h6q,
    "fx"           -> fx,
    "cost"         -> cost,
    "placements"   -> placements,
    "pricing"      -> pricing
  )

  // ingest/pricing/*.ndjson — the real customer price book (doc 24): a governed price_agreement per channel/customer
  // + a price_rule (tier) per (agreement, variant). open_list = the default everyone gets (Retail); segment scopes
  // to party.segment (Installers); customer_set links the named accounts (Octopus, YESSS, …) by name. Nobody types
  // a price — the order line binds to one of these tiers. Idempotent (agreement keyed on name+currency; rule on
  // agreement+variant). Loads after mrpeasy so variants + parties exist.
  private def pricing(@scala.annotation.unused dataset: String, row: Json): ConnectionIO[Int] = {
    val c = row.hcursor
    (
      c.get[String]("agreement").toOption,
      c.get[String]("applies_to").toOption,
      c.get[String]("sku").toOption,
      c.get[String]("currency").toOption,
      c.get[BigDecimal]("price").toOption
    ).tupled match {
      case None => 0.pure[ConnectionIO]
      case Some((agr, applies, sku, ccy, price)) =>
        val scope     = c.get[String]("segment").toOption
        val kw        = c.get[String]("customer_kw").toOption
        val taxRegime = if (ccy == "GBP") "GB_STANDARD" else "IE_STANDARD"
        sql"SELECT id FROM product_variant WHERE sku = $sku".query[UUID].option.flatMap {
          case None => 0.pure[ConnectionIO]
          case Some(vid) =>
            for {
              _ <- sql"""INSERT INTO price_agreement (name, surface, currency, applies_to, base_volume_basis, valid_from, valid_to, terms, status, version, scope_value)
                         SELECT $agr, 'customer', $ccy, $applies, 'per_order', DATE '2026-01-01', DATE '2026-12-31', '{}'::jsonb, 'active', 1, $scope
                         WHERE NOT EXISTS (SELECT 1 FROM price_agreement WHERE name = $agr AND currency = $ccy)""".update.run
              agrId <- sql"SELECT id FROM price_agreement WHERE name = $agr AND currency = $ccy ORDER BY created_at LIMIT 1".query[UUID].unique
              _ <- if (applies == "customer_set" && kw.isDefined)
                sql"""INSERT INTO price_agreement_customer (agreement_id, party_id)
                      SELECT $agrId, p.id FROM party p
                      WHERE p.display_name ILIKE ${"%" + kw.get + "%"}
                        AND NOT EXISTS (SELECT 1 FROM price_agreement_customer pac WHERE pac.agreement_id = $agrId AND pac.party_id = p.id)""".update.run
              else 0.pure[ConnectionIO]
              n <- sql"""INSERT INTO price_rule (surface, product_variant_id, currency, tax_regime, authorised_price, max_discount_pct, min_qty, version, effective_from, status, price_agreement_id)
                         SELECT 'customer', $vid, $ccy, $taxRegime, $price, 0, 1, 1, DATE '2026-01-01', 'active', $agrId
                         WHERE NOT EXISTS (SELECT 1 FROM price_rule WHERE price_agreement_id = $agrId AND product_variant_id = $vid AND min_qty = 1)""".update.run
            } yield n
        }
    }
  }

  // ingest/cost/*.ndjson — per-SKU supplier cost (USD) with quarterly volume-discount bands. One NDJSON row fans
  // out to one supplier_cost row per band. Idempotent on (supplier, sku, min_qty_per_quarter, as_of).
  private def cost(@scala.annotation.unused dataset: String, row: Json): ConnectionIO[Int] = {
    val c = row.hcursor
    (
      c.get[String]("sku").toOption,
      c.get[String]("supplier").toOption,
      c.get[String]("currency").toOption,
      c.get[String]("as_of").toOption.flatMap(s => scala.util.Try(LocalDate.parse(s)).toOption)
    ).tupled match {
      case None => 0.pure[ConnectionIO]
      case Some((sku, supplier, ccy, asOf)) =>
        val ship  = c.get[BigDecimal]("shipping_gbp").toOption.getOrElse(BigDecimal(0))
        val duty  = c.get[BigDecimal]("duty_pct").toOption.getOrElse(BigDecimal(0))
        val src   = c.get[String]("source").toOption
        val bands = c.downField("cost_bands").values.toList.flatten
        val flat  = c.get[BigDecimal]("unit_cost_gbp").toOption // legacy (MRPeasy avg_cost): one flat GBP band
        if (bands.nonEmpty)
          bands
            .traverse { b =>
              val bc = b.hcursor
              (bc.get[Int]("min_qty_per_quarter").toOption, bc.get[BigDecimal]("unit_cost_usd").toOption).tupled match {
                case None => 0.pure[ConnectionIO]
                case Some((minQ, unitCost)) =>
                  sql"""INSERT INTO supplier_cost (supplier, sku, currency, min_qty_per_quarter, unit_cost, shipping_gbp, duty_pct, source, as_of)
                      SELECT $supplier, $sku, $ccy, $minQ, $unitCost, $ship, $duty, $src, $asOf
                      WHERE NOT EXISTS (
                        SELECT 1 FROM supplier_cost WHERE supplier = $supplier AND sku = $sku
                          AND min_qty_per_quarter = $minQ AND as_of = $asOf
                      )""".update.run
              }
            }
            .map(_.sum)
        else
          flat.fold(0.pure[ConnectionIO])(uc =>
            sql"""INSERT INTO supplier_cost (supplier, sku, currency, min_qty_per_quarter, unit_cost, shipping_gbp, duty_pct, source, as_of)
                  SELECT $supplier, $sku, $ccy, 0, $uc, 0, 0, $src, $asOf
                  WHERE NOT EXISTS (SELECT 1 FROM supplier_cost WHERE supplier = $supplier AND sku = $sku AND min_qty_per_quarter = 0 AND as_of = $asOf)""".update.run
          )
    }
  }

  // ingest/fx/rates.ndjson — {"base":"GBP","quote":"USD","rate":1.27,"as_of":"2026-06-01","rate_type":"spot","source":"seed"}
  // The FX rate store (doc 04): presentation/consolidation currency reporting reads the latest rate per pair. A
  // real provider feed lands the same shape; seeded rates bootstrap USD reporting until that's wired.
  private def fx(@scala.annotation.unused dataset: String, row: Json): ConnectionIO[Int] = {
    val c = row.hcursor
    (
      c.get[String]("base").toOption,
      c.get[String]("quote").toOption,
      c.get[BigDecimal]("rate").toOption,
      c.get[String]("as_of").toOption.flatMap(s => scala.util.Try(LocalDate.parse(s)).toOption)
    ).tupled match {
      case None => 0.pure[ConnectionIO]
      case Some((base, quote, rate, asOf)) =>
        val rt  = c.get[String]("rate_type").toOption.getOrElse("spot")
        val src = c.get[String]("source").toOption.getOrElse("seed")
        sql"""INSERT INTO exchange_rate (base, quote, rate, rate_type, as_of, source)
              SELECT $base, $quote, $rate, $rt, $asOf, $src
              WHERE NOT EXISTS (
                SELECT 1 FROM exchange_rate WHERE base = $base AND quote = $quote AND as_of = $asOf AND rate_type = $rt
              )""".update.run
    }
  }

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

  // ingest/hubspot/rma_tickets.ndjson — support RMA tickets (which unit replaced which). Landed raw here; ignition
  // resolves the serials → genealogy pointer + warranty inheritance (serials exist by boot). Idempotent on ticket_ref.
  private def hubspotRmaTicket(row: Json): ConnectionIO[Int] = {
    val c = row.hcursor
    c.get[String]("ticket_ref").toOption.orElse(c.get[String]("ticket_id").toOption) match {
      case None => 0.pure[ConnectionIO]
      case Some(ref) =>
        val orig   = c.get[String]("original_serial").toOption.orElse(c.get[String]("faulty_serial").toOption)
        val repl   = c.get[String]("replacement_serial").toOption.orElse(c.get[String]("new_serial").toOption)
        val tType  = c.get[String]("ticket_type").toOption.orElse(c.get[String]("type").toOption)
        val reason = c.get[String]("reason").toOption
        val opened = c.get[String]("opened_at").toOption.flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
        val closed = c.get[String]("closed_at").toOption.flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
        val status = c.get[String]("status").toOption
        sql"""INSERT INTO rma_ticket (ticket_ref, original_serial, replacement_serial, ticket_type, reason, opened_at, closed_at, status, payload)
              VALUES ($ref, $orig, $repl, $tType, $reason, $opened, $closed, $status, ${row.noSpaces}::jsonb)
              ON CONFLICT (ticket_ref) DO UPDATE SET
                original_serial = EXCLUDED.original_serial, replacement_serial = EXCLUDED.replacement_serial,
                ticket_type = EXCLUDED.ticket_type, reason = EXCLUDED.reason, opened_at = EXCLUDED.opened_at,
                closed_at = EXCLUDED.closed_at, status = EXCLUDED.status, payload = EXCLUDED.payload""".update.run
    }
  }

  // ingest/hubspot/deals_attributed.ndjson is the canonical deal register (company-attributed, dynamic pipeline
  // labels) → deal_snapshot. The older deals_lifecycle/deals_won scrapes are superseded and no longer loaded.
  private def hubspot(dataset: String, row: Json): ConnectionIO[Int] =
    if (dataset == "rma_tickets") hubspotRmaTicket(row)
    else if (dataset == "deals_attributed") hubspotAttributedDeal(row)
    else if (dataset == "contacts") hubspotContact(row)
    else if (dataset == "companies") hubspotCompany(row)
    else if (dataset == "account_match_verdicts") hubspotMatchVerdict(row)
    else 0.pure[ConnectionIO]

  // ingest/hubspot/account_match_verdicts.ndjson → hubspot_match_verdict staging (the model matcher's output).
  // The apply step merges confidence>=0.9 with a target. Empty until the Bedrock matcher runs; idempotent.
  private def hubspotMatchVerdict(row: Json): ConnectionIO[Int] = {
    val c = row.hcursor
    c.get[String]("hs_company_id").toOption match {
      case None => 0.pure[ConnectionIO]
      case Some(id) =>
        val target = c.get[String]("merge_into_name").toOption.filter(_.nonEmpty)
        val conf   = c.get[BigDecimal]("confidence").toOption.orElse(c.get[Double]("confidence").toOption.map(BigDecimal(_)))
        val reason = c.get[String]("reason").toOption
        val model  = c.get[String]("model").toOption.getOrElse("model")
        sql"""INSERT INTO hubspot_match_verdict (hs_company_id, merge_into_name, confidence, reason, model)
              VALUES ($id, $target, ${conf.getOrElse(BigDecimal(0))}, $reason, $model)
              ON CONFLICT (hs_company_id) DO UPDATE SET
                merge_into_name = EXCLUDED.merge_into_name, confidence = EXCLUDED.confidence,
                reason = EXCLUDED.reason, model = EXCLUDED.model""".update.run
    }
  }

  // ingest/hubspot/companies.ndjson → hubspot_company_raw staging (the canonical company universe for correlation).
  private def hubspotCompany(row: Json): ConnectionIO[Int] = {
    val c = row.hcursor
    c.get[String]("company_id").toOption match {
      case None => 0.pure[ConnectionIO]
      case Some(id) =>
        val s = (k: String) => c.get[String](k).toOption.filter(_.nonEmpty)
        sql"""INSERT INTO hubspot_company_raw (company_id, name, domain, industry, country)
              VALUES ($id, ${s("name")}, ${s("domain")}, ${s("industry")}, ${s("country")})
              ON CONFLICT (company_id) DO UPDATE SET
                name = EXCLUDED.name, domain = EXCLUDED.domain, industry = EXCLUDED.industry, country = EXCLUDED.country""".update.run
    }
  }

  // ingest/placements/serial_owner.ndjson → placement_owner_raw staging (serial → owner email/name). The
  // correlation step materializes an individual master account per owner and stamps serial_unit.owner_party_id.
  private def placements(@scala.annotation.unused dataset: String, row: Json): ConnectionIO[Int] = {
    val c = row.hcursor
    c.get[String]("serial").toOption match {
      case None => 0.pure[ConnectionIO]
      case Some(serial) =>
        val s = (k: String) => c.get[String](k).toOption.filter(_.nonEmpty)
        sql"""INSERT INTO placement_owner_raw (serial, device_id, placement_id, keycloak_user_id, owner_email, owner_name, placement_name, country)
              VALUES ($serial, ${s("device_id")}, ${s("placement_id")}, ${s("keycloak_user_id")}, ${s("owner_email")},
                      ${s("owner_name")}, ${s("placement_name")}, ${s("country")})
              ON CONFLICT (serial) DO UPDATE SET
                device_id = EXCLUDED.device_id, placement_id = EXCLUDED.placement_id, keycloak_user_id = EXCLUDED.keycloak_user_id,
                owner_email = EXCLUDED.owner_email, owner_name = EXCLUDED.owner_name, placement_name = EXCLUDED.placement_name,
                country = EXCLUDED.country""".update.run
    }
  }

  // ingest/hubspot/contacts.ndjson → hubspot_contact_raw staging. The MDM correlation step materializes these into
  // `contact` once each contact's HubSpot company is bound to a master party. Idempotent on contact_id.
  private def hubspotContact(row: Json): ConnectionIO[Int] = {
    val c = row.hcursor
    c.get[String]("contact_id").toOption match {
      case None => 0.pure[ConnectionIO]
      case Some(id) =>
        val s   = (k: String) => c.get[String](k).toOption.filter(_.nonEmpty)
        val created = s("created").flatMap(v => scala.util.Try(LocalDate.parse(v)).toOption)
        sql"""INSERT INTO hubspot_contact_raw
                (contact_id, email, first_name, last_name, phone, company, company_id, job_title, lifecycle, created)
              VALUES ($id, ${s("email")}, ${s("first_name")}, ${s("last_name")}, ${s("phone")}, ${s("company")},
                      ${s("company_id")}, ${s("job_title")}, ${s("lifecycle")}, $created)
              ON CONFLICT (contact_id) DO UPDATE SET
                email = EXCLUDED.email, first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name,
                phone = EXCLUDED.phone, company = EXCLUDED.company, company_id = EXCLUDED.company_id,
                job_title = EXCLUDED.job_title, lifecycle = EXCLUDED.lifecycle, created = EXCLUDED.created""".update.run
    }
  }

  // ingest/hubspot/deals_attributed.ndjson → deal_snapshot WITH company attribution (deal → installer/wholesaler/
  // retail customer). Supersedes deals_lifecycle: same substrate plus company_id/company_name/segment, so the desk
  // shows a per-company deal/PO book and demand attributes to a customer. Idempotent on deal_id.
  private def hubspotAttributedDeal(row: Json): ConnectionIO[Int] = {
    val c = row.hcursor
    (
      c.get[String]("deal_id").toOption,
      c.get[String]("created").toOption.flatMap(s => scala.util.Try(LocalDate.parse(s)).toOption),
      c.get[String]("pipeline").toOption
    ).tupled match {
      case None => 0.pure[ConnectionIO]
      case Some((dealId, created, pipeline)) =>
        val closed = c.get[String]("closed").toOption.flatMap(s => scala.util.Try(LocalDate.parse(s)).toOption)
        val won    = c.get[Boolean]("won").toOption.getOrElse(false)
        val isClosed = c.get[Boolean]("is_closed").toOption.getOrElse(false) || won
        val amount = c.get[String]("amount").toOption.flatMap(s => scala.util.Try(BigDecimal(s)).toOption)
        val companyId   = c.get[String]("company_id").toOption
        val companyName = c.get[String]("company_name").toOption
        val segment     = c.get[String]("segment").toOption
        sql"""INSERT INTO deal_snapshot
                (deal_id, pipeline, created_at, closed_at, is_won, is_closed, amount, company_id, company_name, segment)
              VALUES ($dealId, $pipeline, $created, $closed, $won, $isClosed,
                      ${amount.getOrElse(BigDecimal(0))}, $companyId, $companyName, $segment)
              ON CONFLICT (deal_id) DO UPDATE SET
                pipeline = EXCLUDED.pipeline, created_at = EXCLUDED.created_at, closed_at = EXCLUDED.closed_at,
                is_won = EXCLUDED.is_won, is_closed = EXCLUDED.is_closed, amount = EXCLUDED.amount,
                company_id = EXCLUDED.company_id, company_name = EXCLUDED.company_name, segment = EXCLUDED.segment""".update.run
    }
  }

  // ingest/ghostbusters/activations.ndjson — the SELL-THROUGH half of the serial ledger (prod Athena
  // charger_activation, first activation per V3 serial). The ship guard tolerates a 7-day clock skew but
  // refuses activations materially before dispatch (refurb/RMA serials re-entering). Loads after mrpeasy
  // (lexical order) so the serials exist.
  private def ghostbusters(dataset: String, row: Json): ConnectionIO[Int] =
    if (dataset != "activations") 0.pure[ConnectionIO]
    else {
      val c = row.hcursor
      (
        c.get[String]("serial").toOption,
        c.get[String]("activated_at").toOption.flatMap(s => scala.util.Try(Instant.parse(s)).toOption)
      ).tupled match {
        case None                        => 0.pure[ConnectionIO]
        case Some((serial, activatedAt)) =>
          // Flip BOTH the timestamp and the lifecycle status — the shelf board (and ATP) count status='activated',
          // so setting only activated_at left every account reading 0 activated / shipped on-shelf.
          sql"""UPDATE serial_unit su SET activated_at = $activatedAt, status = 'activated'
                FROM dispatch d
                WHERE su.serial_no = $serial AND d.id = su.dispatch_id AND su.activated_at IS NULL
                  AND $activatedAt >= COALESCE(d.delivered_at, d.date::timestamptz) - interval '7 days'""".update.run
      }
    }

  // ingest/h6q/coverage.ndjson — the REAL H6Q workbook forecast (doc 24/26): finance's top-down monthly P50
  // volume per channel, split to per-SKU via the historical mix (largest-remainder) by local/import_xlsx.py
  // --ndjson. Lands at market level in pipeline_coverage — the table the H6Q board (coverage/matrix) reads. The
  // model-authored forecast_entry rows are a separate substrate (the backtest engine). toggle null = base P50,
  // "ex_motability" = the workbook's ex-cut.
  private def h6q(dataset: String, row: Json): ConnectionIO[Int] =
    if (dataset != "coverage") 0.pure[ConnectionIO]
    else {
      val c = row.hcursor
      (
        str(c, "sku"),
        str(c, "period_month").flatMap(s => scala.util.Try(LocalDate.parse(s)).toOption),
        num(c, "forecast_qty").map(_.toInt)
      ).tupled match {
        case None => 0.pure[ConnectionIO]
        case Some((sku, period, qty)) =>
          val toggle = str(c, "toggle_basis") // None = base P50, Some("ex_motability") = the ex-cut
          h6qVariant(sku).flatMap { variantId =>
            h6qScenario(toggle).flatMap {
              case None             => 0.pure[ConnectionIO]
              case Some(scenarioId) =>
                // uq_pipeline_coverage_dim is a UNIQUE INDEX over a COALESCE expression (not a named constraint),
                // so ON CONFLICT must restate that exact expression to use it as the arbiter.
                sql"""INSERT INTO pipeline_coverage
                        (level, market_id, product_variant_id, period_month, scenario_id, forecast_qty, forecast_source)
                      SELECT 'market', m.id, $variantId, $period, $scenarioId, $qty, 'h6q_workbook'
                      FROM market m WHERE m.code = 'UK'
                      ON CONFLICT (level,
                                   COALESCE(channel_id, '00000000-0000-0000-0000-000000000000'::uuid),
                                   COALESCE(sub_channel_id, '00000000-0000-0000-0000-000000000000'::uuid),
                                   COALESCE(segment, ''::text),
                                   COALESCE(sector, ''::text),
                                   COALESCE(company_id, '00000000-0000-0000-0000-000000000000'::uuid),
                                   COALESCE(branch_company_id, '00000000-0000-0000-0000-000000000000'::uuid),
                                   COALESCE(agent_user_id, '00000000-0000-0000-0000-000000000000'::uuid),
                                   COALESCE(market_id, '00000000-0000-0000-0000-000000000000'::uuid),
                                   COALESCE(product_variant_id, '00000000-0000-0000-0000-000000000000'::uuid),
                                   period_month, scenario_id)
                      DO UPDATE SET forecast_qty = EXCLUDED.forecast_qty,
                                    forecast_source = EXCLUDED.forecast_source""".update.run
            }
          }
      }
    }

  // The workbook's 9 charger SKUs (HV-5M-W … HV-10M-G) under a charger family — upsert so the snapshot is self-contained.
  private def h6qVariant(sku: String): ConnectionIO[UUID] =
    sql"SELECT id FROM product_variant WHERE sku = $sku".query[UUID].option.flatMap {
      case Some(id) => id.pure[ConnectionIO]
      case None =>
        sql"SELECT id FROM product_family WHERE code = 'hv-charger'"
          .query[UUID]
          .option
          .flatMap {
            case Some(f) => f.pure[ConnectionIO]
            case None =>
              sql"INSERT INTO product_family (code, name) VALUES ('hv-charger', 'Hypervolt Charger') RETURNING id"
                .query[UUID]
                .unique
          }
          .flatMap { fam =>
            sql"""INSERT INTO product_variant (family_id, sku, generation, product_class)
                  VALUES ($fam, $sku, 'v3', 'charger') RETURNING id""".query[UUID].unique
          }
    }

  // type=P50, distinguished by toggle_basis (null = base, ex_motability = the workbook's ex-cut).
  private def h6qScenario(toggle: Option[String]): ConnectionIO[Option[UUID]] =
    sql"SELECT id FROM forecast_scenario WHERE type = 'P50' AND toggle_basis IS NOT DISTINCT FROM $toggle ORDER BY id LIMIT 1"
      .query[UUID]
      .option

  // ingest/mrpeasy/*.ndjson — the B2B system of record (doc 26 §3a): real customer orders (units, real SKUs,
  // real accounts) and shipments (with serial numbers). HubSpot stopped being the deal record in Oct'25;
  // MRPeasy carries the 2021→today trade history. Orders load before shipments (lexical file order).
  private def mrpeasy(dataset: String, row: Json): ConnectionIO[Int] =
    dataset match {
      case "customer_orders" => mrpOrder(row)
      case "shipments"       => mrpShipment(row)
      case _                 => 0.pure[ConnectionIO]
    }

  private def str(c: io.circe.ACursor, k: String): Option[String] =
    c.downField(k).focus.flatMap(j => j.asString.orElse(j.asNumber.map(_.toString))).filter(_.nonEmpty)

  private def num(c: io.circe.ACursor, k: String): Option[BigDecimal] =
    str(c, k).flatMap(s => scala.util.Try(BigDecimal(s)).toOption)

  private def epoch(c: io.circe.ACursor, k: String): Option[java.time.LocalDateTime] =
    str(c, k)
      .flatMap(s => scala.util.Try(s.toLong).toOption)
      .map(t => java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(t), java.time.ZoneOffset.UTC))

  private def skippableSku(code: String): Boolean =
    code.contains("DELIVERY") || code.contains("DONOTUSE")

  // the channel taxonomy the live comparables view groups by — set at insert so new accounts never vanish
  // from channel_comparables with a NULL segment
  private def segmentOf(name: String): String = {
    val n      = name.toLowerCase
    val energy = List("octopus", "e.on", "eon energy", "edf", "ovo", "british gas", "scottish power", "shell")
    val wholesale =
      List("yesss", "rexel", "cef", "city electrical", "medlock", "kelvelec", "edmundson", "denmans", "wolseley")
    val online = List("smart home charge", "ev store", "evec", "amazon")
    if (energy.exists(n.contains)) "energy"
    else if (wholesale.exists(n.contains)) "wholesale"
    else if (online.exists(n.contains)) "online_retail"
    else "installers"
  }

  private def mrpParty(name: String): ConnectionIO[UUID] =
    sql"SELECT id FROM party WHERE display_name = ${"MRP: " + name}".query[UUID].option.flatMap {
      case Some(id) => id.pure[ConnectionIO]
      case None =>
        sql"""INSERT INTO party (display_name, party_type, is_organization, segment, market_id)
              VALUES (${"MRP: " + name}, 'wholesaler', true, ${segmentOf(name)},
                      (SELECT id FROM market WHERE code = 'UK')) RETURNING id"""
          .query[UUID]
          .unique
    }

  private def mrpVariant(sku: String): ConnectionIO[UUID] =
    sql"SELECT id FROM product_variant WHERE sku = $sku".query[UUID].option.flatMap {
      case Some(id) => id.pure[ConnectionIO]
      case None =>
        sql"SELECT id FROM product_family WHERE code = 'MRP'"
          .query[UUID]
          .option
          .flatMap {
            case Some(f) => f.pure[ConnectionIO]
            case None =>
              sql"INSERT INTO product_family (code, name) VALUES ('MRP', 'MRPeasy import') RETURNING id"
                .query[UUID]
                .unique
          }
          .flatMap { fam =>
            // serialization-derived (the 0301 serial log): HV-PR-1070/117x/1180/137 are finished-goods trade
            // SKUs that ship WITH serials; every other HV-PR code is a component (never serialized, bulk
            // quantities to the manufacturing partner). Prefix alone misclassified 18k charger units as parts.
            val serializedTradeSku = sku.matches("HV-PR-(1070|117[2-9]|1180|137).*")
            val cls =
              if (sku.startsWith("HV-PR") && !serializedTradeSku) "part"
              else if (sku.startsWith("HYPV-HOLS") || sku.startsWith("GD1")) "accessory"
              else "charger"
            sql"""INSERT INTO product_variant (family_id, sku, generation, product_class)
                VALUES ($fam, $sku, 'mrp', $cls) RETURNING id""".query[UUID].unique
          }
    }

  private def mrpOrder(row: Json): ConnectionIO[Int] = {
    val c = row.hcursor
    (str(c, "code"), str(c, "customer_name"), epoch(c, "created")).tupled match {
      case None => 0.pure[ConnectionIO]
      case Some((code, customer, created)) =>
        val status = if (str(c, "status").exists(_.toLowerCase.contains("cancel"))) "cancelled" else "placed"
        val total  = num(c, "total_price_cur").orElse(num(c, "total_price")).getOrElse(BigDecimal(0))
        mrpParty(customer).flatMap { party =>
          sql"""INSERT INTO "order" (order_no, type, sold_to_party_id, bill_to_party_id, status, txn_currency,
                                     payment_method, subtotal_ex_vat, vat_total, total_inc_vat, market_id, order_date)
                VALUES (${"MRP-" + code}, 'trade', $party, $party, $status, 'GBP', 'invoice', $total, 0, $total,
                        (SELECT id FROM market WHERE code = 'UK'), $created)
                ON CONFLICT (order_no) DO NOTHING RETURNING id""".query[UUID].option.flatMap {
            case None => 0.pure[ConnectionIO] // already loaded — idempotent
            case Some(orderId) =>
              val lines = c.downField("lines").focus.flatMap(_.asArray).getOrElse(Vector.empty)
              lines.toList.traverse_ { l =>
                val lc = l.hcursor
                (str(lc, "item_code").filterNot(skippableSku), num(lc, "qty").filter(_ > 0)).tupled match {
                  case None => 0.pure[ConnectionIO].void
                  case Some((sku, qty)) =>
                    mrpVariant(sku).flatMap(variant =>
                      sql"""INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat,
                                                    vat_amount, line_total_inc_vat)
                            VALUES ($orderId, $variant, $qty, ${num(lc, "price").getOrElse(BigDecimal(0))}, 0,
                                    ${num(lc, "total").getOrElse(BigDecimal(0))})""".update.run.void
                    )
                }
              } *> sql"""UPDATE "order" SET created_at = $created WHERE id = $orderId""".update.run
          }
        }
    }
  }

  private def mrpShipment(row: Json): ConnectionIO[Int] = {
    val c     = row.hcursor
    val isRma = str(c, "rma_order_id").isDefined
    (str(c, "code"), str(c, "order_code"), epoch(c, "created")).tupled match {
      case None                                                 => 0.pure[ConnectionIO]
      case Some((code, _, _)) if isRma || code.contains("-rtn") => 0.pure[ConnectionIO]
      case Some((code, orderCode, created)) =>
        sql"""SELECT id, sold_to_party_id FROM "order" WHERE order_no = ${"MRP-" + orderCode}"""
          .query[(UUID, UUID)]
          .option
          .flatMap {
            case None => 0.pure[ConnectionIO] // shipment without a loaded order — skip, never fail
            case Some((orderId, company)) =>
              sql"""INSERT INTO dispatch (dispatch_no, order_id, date, delivered_at)
                    VALUES (${"MRP-" + code}, $orderId, $created, ${epoch(c, "delivery_date")})
                    ON CONFLICT (dispatch_no) DO NOTHING RETURNING id""".query[UUID].option.flatMap {
                case None => 0.pure[ConnectionIO]
                case Some(dispatchId) =>
                  val lines = c.downField("lines").focus.flatMap(_.asArray).getOrElse(Vector.empty)
                  lines.toList
                    .traverse { l =>
                      val lc = l.hcursor
                      str(lc, "item_code").filterNot(skippableSku) match {
                        case None => 0.pure[ConnectionIO]
                        case Some(sku) =>
                          val serials = lc
                            .downField("serials")
                            .focus
                            .flatMap(_.asArray)
                            .getOrElse(Vector.empty)
                            .flatMap(_.asString)
                            .filter(_.startsWith("0301"))
                          mrpVariant(sku).flatMap { variant =>
                            val qty = num(lc, "qty").getOrElse(BigDecimal(serials.size))
                            val line =
                              sql"""INSERT INTO dispatch_line (dispatch_id, order_line_id, qty)
                                    SELECT $dispatchId, ol.id, $qty FROM order_line ol
                                    WHERE ol.order_id = $orderId AND ol.product_variant_id = $variant
                                    LIMIT 1""".update.run
                            line *> serials.toList
                              .traverse { s =>
                                sql"""INSERT INTO serial_unit (serial_no, generation, product_variant_id,
                                                             dispatch_id, company_id, status)
                                    VALUES ($s, 'v3', $variant, $dispatchId, $company, 'dispatched')
                                    ON CONFLICT (serial_no) DO NOTHING""".update.run
                              }
                              .map(_.sum)
                          }
                      }
                    }
                    .map(_.sum + 1)
              }
          }
    }
  }
}
