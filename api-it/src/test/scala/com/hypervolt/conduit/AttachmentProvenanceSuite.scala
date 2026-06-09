package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.close.LineageService
import com.hypervolt.conduit.document.AttachmentInput
import com.hypervolt.conduit.document.AttachmentService
import com.hypervolt.conduit.document.S3DocumentStorage
import com.hypervolt.conduit.order.OrderService
import com.hypervolt.conduit.order.PlaceLineInput
import com.hypervolt.conduit.order.PlaceOrderInput
import com.hypervolt.conduit.pricing.AgreementService
import com.hypervolt.conduit.pricing.TierBand
import com.hypervolt.conduit.pricing.TierRequest
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import weaver.IOSuite

// M13-Docs.9 (doc 25) — associated documents + the revenue-provenance trace, the Octopus shape end-to-end:
// the signed supply agreement (the contract the tiers were entered from) attaches to the price_agreement; the
// customer's PO (HK00552) is stored on receipt, the order is created FROM it (source_attachment_id +
// customer_po_number) and priced from the AGREEMENT (never the PO's stated prices); the PO total is reconciled
// against the resolved total (drift flagged, never silent); and ONE provenance call walks the whole chain:
// order → source PO → per-line price agreement → the signed contract. Bytes live on the WORM store (LocalStack S3),
// sha256-deduped and tamper-evidenced.
object AttachmentProvenanceSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], S3DocumentStorage[IO])
  override def maxParallelism: Int = 1
  override def sharedResource: Resource[IO, Res] =
    (TestPostgres.transactor, TestLocalStackS3.storage).tupled

  private val channel = UUID.randomUUID()
  private val market  = UUID.randomUUID()

  private val contractPdf = "SIGNED OCTOPUS SUPPLY AGREEMENT + SCHEDULE 3 RATE CARD".getBytes(StandardCharsets.UTF_8)
  private val poPdf       = "OCTOPUS PURCHASE ORDER HK00552 — 960 x Home 3 Pro".getBytes(StandardCharsets.UTF_8)

  private def seedCharger(xa: HikariTransactor[IO]): IO[(UUID, String)] = {
    val sku = "HV3-" + UUID.randomUUID().toString.take(8)
    (for {
      fam <- sql"INSERT INTO product_family (code, name) VALUES (${"F-" + sku}, 'f') RETURNING id".query[UUID].unique
      vid <-
        sql"INSERT INTO product_variant (family_id, sku, generation) VALUES ($fam,$sku,'g3') RETURNING id"
          .query[UUID]
          .unique
    } yield (vid, sku)).transact(xa)
  }

  private def party(xa: HikariTransactor[IO], n: String): IO[UUID] =
    sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ($n,'wholesaler',true) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def attach(
      service: AttachmentService[IO],
      kind: String,
      subjectType: String,
      subjectId: UUID,
      bytes: Array[Byte],
      ref: Option[String],
      metadata: Json = Json.obj()
  ): IO[UUID] =
    service.store(
      AttachmentInput(
        "inbound",
        kind,
        subjectType,
        subjectId,
        s"$kind.pdf",
        "application/pdf",
        bytes,
        ref,
        "upload",
        Some(UUID.randomUUID()),
        Some("commercial"),
        metadata
      )
    )

  test("the Octopus chain: contract on the agreement, order from the PO, ONE call traces revenue to both") {
    case (xa, storage) =>
      val attachments = new AttachmentService[IO](xa, storage)
      val agreements  = new AgreementService[IO](xa)
      val orders      = new OrderService[IO](xa)
      val lineage     = new LineageService[IO](xa)
      for {
        (vid, sku) <- seedCharger(xa)
        octopus    <- party(xa, "Octopus Energy")
        // the governed agreement (the tiers were entered FROM the signed contract)
        agreement <- agreements.request(
          TierRequest(
            "Octopus supply agreement",
            "GBP",
            List(octopus),
            List(TierBand(vid, 1, None, BigDecimal("480.00"), "GB_STANDARD")),
            Instant.now().minusSeconds(3600),
            None,
            "per_order",
            Json.obj(),
            Some("signed MSA"),
            UUID.randomUUID()
          )
        )
        _ <- agreements.activate(agreement, UUID.randomUUID())
        // attach the signed contract + schedule to the agreement — the tier provenance
        contractId <-
          attach(attachments, "signed_contract", "price_agreement", agreement, contractPdf, Some("OCTO-MSA-2024"))
        // the PO arrives: stored at receipt against the customer, sha256'd onto the WORM store
        poAtParty <- attach(
          attachments,
          "customer_po",
          "party",
          octopus,
          poPdf,
          Some("HK00552"),
          Json.obj("po_total" -> Json.fromString("460800.00"))
        )
        // idempotent re-upload: the same bytes to the same subject resolve to the SAME attachment
        poAgain <- attach(attachments, "customer_po", "party", octopus, poPdf, Some("HK00552"))
        // the order is created FROM the PO — priced from the AGREEMENT, never the PO's stated prices
        placed <- orders.place(
          PlaceOrderInput(
            "trade",
            None,
            octopus,
            octopus,
            channel,
            market,
            "GBP",
            "stripe",
            Some("HK00552"),
            None,
            None,
            List(PlaceLineInput(sku, 960, None, Nil)),
            None,
            Some(poAtParty)
          ),
          Instant.now()
        )
        order = placed.toOption.get
        // the PO also attaches to the order it became
        _ <- attach(
          attachments,
          "customer_po",
          "order",
          order.id,
          poPdf,
          Some("HK00552"),
          Json.obj("po_total" -> Json.fromString("460800.00"))
        )
        // ONE call: the contractual sources of this order's revenue
        trace <- lineage.contractualSources(order.id)
        // WORM round-trip: the stored contract bytes come back intact
        downloaded <- attachments.download(contractId)
        c              = trace.hcursor
        agreementsJson = c.downField("price_agreements").focus.flatMap(_.asArray).getOrElse(Vector.empty)
        orderDocs      = c.downField("order_documents").focus.flatMap(_.asArray).getOrElse(Vector.empty)
        agreementDocs =
          agreementsJson.headOption.toVector
            .flatMap(_.hcursor.downField("documents").focus.flatMap(_.asArray).getOrElse(Vector.empty))
      } yield expect(poAgain == poAtParty) and                                                  // sha-dedupe: one stored object
        expect(order.subtotalExVat == BigDecimal("460800.00")) and                              // 960 × 480 — the AGREEMENT price
        expect(c.get[String]("customer_po_number").contains("HK00552")) and                     // the PO ref on the order
        expect(c.get[String]("source_attachment").contains(poAtParty.toString)) and             // the PO it was created from
        expect(orderDocs.exists(_.hcursor.get[String]("external_ref").contains("HK00552"))) and // the PO document
        expect(agreementsJson.exists(_.hcursor.get[String]("agreement_id").contains(agreement.toString))) and
        expect(agreementDocs.exists(_.hcursor.get[String]("kind").contains("signed_contract"))) and // the contract
        expect(downloaded.exists(_._3.sameElements(contractPdf)))                                   // WORM bytes intact
  }

  test("PO reconciliation: the stated total vs the resolved total — a match is clean, drift is flagged") {
    case (xa, storage) =>
      val attachments = new AttachmentService[IO](xa, storage)
      val agreements  = new AgreementService[IO](xa)
      val orders      = new OrderService[IO](xa)
      for {
        (vid, sku) <- seedCharger(xa)
        octopus    <- party(xa, "Octopus Recon")
        agreement <- agreements.request(
          TierRequest(
            "Octopus recon agreement",
            "GBP",
            List(octopus),
            List(TierBand(vid, 1, None, BigDecimal("480.00"), "GB_STANDARD")),
            Instant.now().minusSeconds(3600),
            None,
            "per_order",
            Json.obj(),
            None,
            UUID.randomUUID()
          )
        )
        _ <- agreements.activate(agreement, UUID.randomUUID())
        placed <- orders.place(
          PlaceOrderInput(
            "trade",
            None,
            octopus,
            octopus,
            channel,
            market,
            "GBP",
            "stripe",
            Some("HK00553"),
            None,
            None,
            List(PlaceLineInput(sku, 10, None, Nil)),
            None,
            None
          ),
          Instant.now()
        )
        order = placed.toOption.get // 10 × 480 = 4800 ex VAT; 5760 inc VAT (GB 20%)
        // a PO stating the contracted total reconciles clean
        cleanPo <- attach(
          attachments,
          "customer_po",
          "order",
          order.id,
          ("PO HK00553 " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),
          Some("HK00553"),
          Json.obj("po_total" -> Json.fromString("5760.00"))
        )
        clean <- attachments.reconcilePo(cleanPo, order.id)
        // a PO stating off-contract prices (the HK00552 £417/£473 case) is DRIFT — flagged, never silent
        driftPo <- attach(
          attachments,
          "customer_po",
          "order",
          order.id,
          ("PO HK00554 " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),
          Some("HK00554"),
          Json.obj("po_total" -> Json.fromString("5000.00"))
        )
        drift <- attachments.reconcilePo(driftPo, order.id)
        flagged <-
          sql"SELECT metadata->'po_reconciliation'->>'status' FROM document_attachment WHERE id = $driftPo"
            .query[String]
            .unique
            .transact(xa)
      } yield expect(clean.exists(_.hcursor.get[String]("status").contains("match"))) and
        expect(drift.exists(_.hcursor.get[String]("status").contains("drift"))) and
        expect(
          drift.exists(_.hcursor.get[String]("drift").exists(d => BigDecimal(d) == BigDecimal("-760")))
        ) and
        expect(flagged == "drift") // the flag is persisted on the attachment for the review worklist
  }
}
