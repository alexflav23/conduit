package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.pricing.AgreementService
import com.hypervolt.conduit.pricing.QuoteLine
import com.hypervolt.conduit.pricing.QuoteService
import com.hypervolt.conduit.pricing.TierBand
import com.hypervolt.conduit.pricing.TierRequest
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.time.Instant
import java.util.UUID
import weaver.IOSuite

// M-Pricing slice 1 (doc 24 §2/§4(a)/§6) — contract & volume-tiered pricing. Proves: (a) per-order band selection by
// qty on the open_list; (b) a customer-scoped agreement beats the open_list for a named buyer, and only for them;
// (c) the governed price-tier request — draft agreement, maker-checker activation (proposer ≠ approver), and the
// named customer resolves the new tier only AFTER activation. Resolution here is the exact path order placement uses.
object ContractPricingSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private val channel = UUID.randomUUID()
  private val market  = UUID.randomUUID()

  // seed a charger variant + an open_list standard ladder (600 @1+, 550 @100+); returns (variantId, sku)
  private def seedCatalogue(xa: HikariTransactor[IO]): IO[(UUID, String)] = {
    val sku = "HV3-" + UUID.randomUUID().toString.take(8)
    (for {
      fam <- sql"INSERT INTO product_family (code, name) VALUES (${"F-" + sku}, 'fam') RETURNING id".query[UUID].unique
      vid <-
        sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam, $sku, 'g3') RETURNING id"
          .query[UUID]
          .unique
      _ <-
        sql"""INSERT INTO price_rule (surface, product_variant_id, currency, tax_regime, authorised_price, min_qty, status)
              VALUES ('customer', $vid, 'GBP', 'GB_STANDARD', 600.00, 1, 'active')""".update.run
      _ <-
        sql"""INSERT INTO price_rule (surface, product_variant_id, currency, tax_regime, authorised_price, min_qty, status)
              VALUES ('customer', $vid, 'GBP', 'GB_STANDARD', 550.00, 100, 'active')""".update.run
    } yield (vid, sku)).transact(xa)
  }

  private def party(xa: HikariTransactor[IO], name: String): IO[UUID] =
    sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ($name,'wholesaler',true) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def unit(xa: HikariTransactor[IO], sku: String, qty: Int, customer: Option[UUID]): IO[BigDecimal] =
    new QuoteService[IO](xa)
      .quote(channel, market, None, "GBP", List(QuoteLine(sku, qty, None)), customer, Instant.now())
      .map(_.toOption.flatMap(_.lines.headOption).map(_.unitPriceExVat).getOrElse(BigDecimal(-1)))

  test("open_list: per-order band selection by qty (600 @1, 550 @100)") { xa =>
    for {
      (_, sku) <- seedCatalogue(xa)
      p1       <- unit(xa, sku, 1, None)
      p100     <- unit(xa, sku, 100, None)
    } yield expect(p1 == BigDecimal("600.0000")) and expect(p100 == BigDecimal("550.0000"))
  }

  test("a customer-scoped tier beats the open_list, only after governed activation, only for the named buyer") { xa =>
    val proposer = UUID.randomUUID()
    val approver = UUID.randomUUID()
    val svc      = new AgreementService[IO](xa)
    for {
      (vid, sku) <- seedCatalogue(xa)
      octopus    <- party(xa, "Octopus")
      other      <- party(xa, "Other Co")
      // agent files a price-tier request: 520 for Octopus, band 1+. A DRAFT agreement.
      agr <- svc.request(
        TierRequest(
          "Octopus supply",
          "GBP",
          List(octopus),
          List(TierBand(vid, 1, None, BigDecimal("520.00"), "GB_STANDARD")),
          Instant.now().minusSeconds(60),
          None,
          "per_order",
          Json.obj(),
          Some("volume commitment"),
          proposer
        )
      )
      // draft is not active → Octopus still resolves the open_list
      draftPrice <- unit(xa, sku, 1, Some(octopus))
      // maker-checker: the proposer cannot approve their own request
      selfDeny <- svc.activate(agr, proposer)
      ok       <- svc.activate(agr, approver)
      // after activation: Octopus gets the contract tier; an unnamed party still gets the open_list
      octopusPrice <- unit(xa, sku, 1, Some(octopus))
      otherPrice   <- unit(xa, sku, 1, Some(other))
      openPrice    <- unit(xa, sku, 1, None)
    } yield expect(draftPrice == BigDecimal("600.0000")) and // draft does not price
      expect(selfDeny.isLeft) and                            // segregation of duties
      expect(ok.isRight) and
      expect(octopusPrice == BigDecimal("520.0000")) and // contract tier wins for the named buyer
      expect(otherPrice == BigDecimal("600.0000")) and   // others unaffected
      expect(openPrice == BigDecimal("600.0000"))        // open_list intact
  }
}
