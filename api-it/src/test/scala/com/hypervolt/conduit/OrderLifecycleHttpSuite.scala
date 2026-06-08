package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.OrderLifecycleRoutes
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import io.circe.parser.{parse => parseJson}
import java.util.UUID
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s.headers.Authorization
import weaver.IOSuite

// M13-Void.5b — the /orders/{id}/lifecycle surface. Replays the collection ledger for an order; view-gated on
// `order`, with the cycle money walled to the principal's order layers (finance sees it; a volume-only viewer
// does not). The timeline (structure) stays visible either way.
object OrderLifecycleHttpSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def viewer(xa: HikariTransactor[IO], layers: List[String]): IO[String] = {
    val kc = s"life-${UUID.randomUUID()}"
    (for {
      uid <- AdminRepo.ensureUser(kc, Some("LifeViewer"))
      r   <- AdminRepo.createRole(s"liferole-${UUID.randomUUID()}", Some("lifecycle viewer"))
      _   <- AdminRepo.addPermission(r, "order", "view", None, layers, Nil, "all")
      _   <- AdminRepo.assign(uid, r, Nil, Nil, Nil, None)
    } yield kc).transact(xa)
  }

  // A role with a grant on something other than `order` → no view:order → 403.
  private def noViewer(xa: HikariTransactor[IO]): IO[String] = {
    val kc = s"nolife-${UUID.randomUUID()}"
    (for {
      uid <- AdminRepo.ensureUser(kc, Some("NoLife"))
      r   <- AdminRepo.createRole(s"noorder-${UUID.randomUUID()}", Some("no order grant"))
      _   <- AdminRepo.addPermission(r, "control", "view", None, Nil, Nil, "all")
      _   <- AdminRepo.assign(uid, r, Nil, Nil, Nil, None)
    } yield kc).transact(xa)
  }

  private def orderWithInvoice(xa: HikariTransactor[IO]): IO[UUID] =
    (for {
      e <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      billTo <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Life Cust','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
      ord <-
        sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method, total_inc_vat)
                  VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', $e, $billTo, $billTo, 'placed', 'GBP', 'invoice', 1200.00) RETURNING id"""
          .query[UUID]
          .unique
      _ <-
        sql"INSERT INTO order_invoice (order_id, invoice_no, total_ex_vat, vat_total, total_inc_vat, status) VALUES ($ord, ${s"INV-${UUID.randomUUID()}"}, 1000, 200, 1200, 'open')".update.run
    } yield ord).transact(xa)

  private def get(routes: org.http4s.HttpRoutes[IO], orderId: UUID, kc: String): IO[(Int, Json)] =
    routes.orNotFound
      .run(
        Request[IO](Method.GET, Uri.unsafeFromString(s"/api/v1/orders/$orderId/lifecycle"))
          .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, s"dev:$kc")))
      )
      .flatMap(r => r.bodyText.compile.string.map(b => (r.status.code, parseJson(b).getOrElse(Json.Null))))

  test("a finance-layer viewer sees the cycle money; a volume-only viewer has it stripped; no-view → 403") { xa =>
    val routes = new OrderLifecycleRoutes[IO](xa, new AuthService[IO](xa, devMode = true)).routes
    for {
      fin                <- viewer(xa, List("volume", "commercial", "pii"))
      vol                <- viewer(xa, List("volume"))
      no                 <- noViewer(xa)
      ord                <- orderWithInvoice(xa)
      (finCode, finJson) <- get(routes, ord, fin)
      (volCode, volJson) <- get(routes, ord, vol)
      (noCode, _)        <- get(routes, ord, no)
    } yield {
      val finCycle = finJson.hcursor.downField("cycles").downArray
      val volCycle = volJson.hcursor.downField("cycles").downArray
      expect(finCode == 200) and
        expect(finCycle.get[String]("invoice_no").isRight) and                                   // structure visible
        expect(finCycle.get[BigDecimal]("total").toOption.contains(BigDecimal("1200.0000"))) and // money visible
        expect(volCode == 200) and
        expect(volCycle.get[String]("invoice_no").isRight) and // structure still visible
        expect(volCycle.downField("total").focus.isEmpty) and  // money walled off
        expect(volCycle.downField("outstanding").focus.isEmpty) and
        expect(noCode == 403)
    }
  }
}
