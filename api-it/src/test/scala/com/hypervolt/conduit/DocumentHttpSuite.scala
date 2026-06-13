package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.DocumentRoutes
import com.hypervolt.conduit.document.DocumentService
import com.hypervolt.conduit.document.DocumentStorage
import com.hypervolt.conduit.document.FopDocumentRenderer
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import io.circe.parser.{parse => parseJson}
import java.util.UUID
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.headers.Authorization
import weaver.IOSuite

// M13-Docs.5 — the /documents surface (doc 17 §6/§9). Lists the legal artefacts for an order/invoice, serves one
// document's metadata, and downloads the PDF bytes from the WORM store. View-gated on `document`; the money on
// the artefact is commercial-layer and is stripped for a principal lacking that layer (the data-layer wall).
object DocumentHttpSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  // finance: full document view incl. commercial layer (the V1_0_27 seed grants finance view:document).
  private def financeUser(xa: HikariTransactor[IO]): IO[String] = {
    val kc = s"fin-${UUID.randomUUID()}"
    (for {
      uid <- AdminRepo.ensureUser(kc, Some("Finance"))
      r   <- sql"SELECT id FROM role WHERE name='finance'".query[UUID].unique
      _   <- AdminRepo.assign(uid, r, Nil, Nil, Nil, Nil, None)
    } yield kc).transact(xa)
  }

  // A principal that can VIEW documents but only at the volume layer — money fields must be projected away.
  private def volumeOnlyViewer(xa: HikariTransactor[IO]): IO[String] = {
    val kc = s"vol-${UUID.randomUUID()}"
    (for {
      uid <- AdminRepo.ensureUser(kc, Some("VolumeOnly"))
      r   <- AdminRepo.createRole(s"docvol-${UUID.randomUUID()}", Some("volume-only document viewer"))
      _   <- AdminRepo.addPermission(r, "document", "view", None, List("volume"), Nil, "all")
      _   <- AdminRepo.assign(uid, r, Nil, Nil, Nil, Nil, None)
    } yield kc).transact(xa)
  }

  // A principal with no document grant at all → 403.
  private def noDocsUser(xa: HikariTransactor[IO]): IO[String] = {
    val kc = s"nodocs-${UUID.randomUUID()}"
    (for {
      uid <- AdminRepo.ensureUser(kc, Some("NoDocs"))
      r   <- sql"SELECT id FROM role WHERE name='retail_sales_agent'".query[UUID].unique
      _   <- AdminRepo.assign(uid, r, Nil, Nil, Nil, Nil, None)
    } yield kc).transact(xa)
  }

  // A finalised invoice document (entity+series+GB/en template seeded by V1_0_27 + product + order + invoice),
  // generated through the real FOP engine into the shared in-memory store. Returns (documentId, invoiceNo).
  private def aDocument(xa: HikariTransactor[IO], docs: DocumentService[IO]): IO[(UUID, String)] =
    for {
      ids <- (for {
          e <-
            sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('HV UK','GB','GBP','operating') RETURNING id"
              .query[UUID]
              .unique
          _ <-
            sql"INSERT INTO document_number_series (entity_id, document_type, jurisdiction, series_code, format) VALUES ($e,'invoice','GB','HV-UK-INV','{series}-{yyyy}-{seq:06d}')".update.run
          fam <-
            sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'Home 3') RETURNING id"
              .query[UUID]
              .unique
          v <-
            sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', false) RETURNING id"
              .query[UUID]
              .unique
          billTo <-
            sql"INSERT INTO party (display_name, legal_name, party_type, is_organization) VALUES ('Doc Cust','Doc Customer Ltd','wholesaler',true) RETURNING id"
              .query[UUID]
              .unique
          _ <-
            sql"INSERT INTO billing_profile (party_id, billing_name, currency, payment_terms_days, invoice_locale) VALUES ($billTo,'Doc','GBP',30,'en')".update.run
          ord <-
            sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
                     VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $e, $billTo, $billTo, 'placed', 'GBP', 'invoice', 1000.00, 200.00, 1200.00) RETURNING id"""
              .query[UUID]
              .unique
          _ <-
            sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount) VALUES ($ord, $v, 2, 500.00, 200.00)".update.run
          no = s"INV-${UUID.randomUUID()}"
          inv <-
            sql"INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat) VALUES ($ord, $no, 1000.00, 200.00, 1200.00) RETURNING id"
              .query[UUID]
              .unique
        } yield (inv, no)).transact(xa)
      (inv, no) = ids
      r <- docs.generateInvoice(inv).map(_.toOption.get)
    } yield (r.documentId, no)

  private def get(routes: org.http4s.HttpRoutes[IO], path: String, kc: String): IO[(Int, String)] =
    routes.orNotFound
      .run(
        Request[IO](Method.GET, Uri.unsafeFromString(path))
          .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, s"dev:$kc")))
      )
      .flatMap(r => r.bodyText.compile.string.map(b => (r.status.code, b)))

  private def getBytes(routes: org.http4s.HttpRoutes[IO], path: String, kc: String): IO[(Int, Array[Byte])] =
    routes.orNotFound
      .run(
        Request[IO](Method.GET, Uri.unsafeFromString(path))
          .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, s"dev:$kc")))
      )
      .flatMap(r => r.body.compile.to(Array).map(b => (r.status.code, b)))

  test("finance lists documents by invoice_no, reads metadata, and downloads the real PDF") { xa =>
    val auth = new AuthService[IO](xa, devMode = true)
    for {
      storage <- DocumentStorage.inMemory[IO]
      docs   = new DocumentService[IO](xa, new FopDocumentRenderer[IO], storage)
      routes = new DocumentRoutes[IO](xa, auth, storage).routes
      fin <- financeUser(xa)
      d   <- aDocument(xa, docs)
      (docId, no) = d
      (lc, lb) <- get(routes, s"/api/v1/documents?invoice_no=$no", fin)
      listJson = parseJson(lb).getOrElse(Json.Null)
      (mc, mb) <- get(routes, s"/api/v1/documents/$docId", fin)
      metaJson = parseJson(mb).getOrElse(Json.Null)
      (pc, pdf) <- getBytes(routes, s"/api/v1/documents/$docId/pdf", fin)
    } yield {
      val items = listJson.asArray.getOrElse(Vector.empty)
      expect(lc == 200) and expect(items.size == 1) and
        expect(items.head.hcursor.get[String]("formatted_number").isRight) and
        expect(items.head.hcursor.get[BigDecimal]("total_amount").toOption.contains(BigDecimal("1200.0000"))) and
        expect(mc == 200) and expect(metaJson.hcursor.get[String]("id").toOption.contains(docId.toString)) and
        expect(pc == 200) and expect(new String(pdf.take(5), "US-ASCII") == "%PDF-")
    }
  }

  test("the commercial money on a document is stripped for a volume-only viewer (data-layer wall)") { xa =>
    val auth = new AuthService[IO](xa, devMode = true)
    for {
      storage <- DocumentStorage.inMemory[IO]
      docs   = new DocumentService[IO](xa, new FopDocumentRenderer[IO], storage)
      routes = new DocumentRoutes[IO](xa, auth, storage).routes
      vol <- volumeOnlyViewer(xa)
      d   <- aDocument(xa, docs)
      (docId, _) = d
      (mc, mb) <- get(routes, s"/api/v1/documents/$docId", vol)
      j = parseJson(mb).getOrElse(Json.Null)
    } yield expect(mc == 200) and
      expect(j.hcursor.get[String]("formatted_number").isRight) and // logistics/identity stays
      expect(j.hcursor.downField("total_amount").focus.isEmpty) and // commercial money walled off
      expect(j.hcursor.downField("currency").focus.isEmpty)
  }

  test("a principal without document view is forbidden; an unknown id is 404") { xa =>
    val auth = new AuthService[IO](xa, devMode = true)
    for {
      storage <- DocumentStorage.inMemory[IO]
      routes = new DocumentRoutes[IO](xa, auth, storage).routes
      nodocs         <- noDocsUser(xa)
      fin            <- financeUser(xa)
      (forbidden, _) <- get(routes, s"/api/v1/documents?invoice_no=NOPE", nodocs)
      (missing, _)   <- get(routes, s"/api/v1/documents/${UUID.randomUUID()}", fin)
    } yield expect(forbidden == 403) and expect(missing == 404)
  }
}
