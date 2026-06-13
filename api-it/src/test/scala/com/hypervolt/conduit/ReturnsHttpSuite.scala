package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.access.AdminRepo
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.ReturnRoutes
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.consumer.ReturnConsumer
import com.hypervolt.conduit.event.EventEnvelope
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.ledger._
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.returns.ReturnService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import io.circe.parser.{parse => parseJson}
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.UUID
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.headers.Authorization
import weaver.IOSuite

// M9b — the returns REST surface (doc 09 §L). The API drives raise/assess/approve/receive synchronously (real
// 403 SoD), records disposition/refund as command events (no TigerBeetle in the API), and the ReturnConsumer
// effects the money. This suite drives the real routes, then replays the real consumer extractor + ReturnService
// — mirroring InvoiceVoidHttpSuite. It also proves the data-layer wall: a volume-only viewer sees no refund.
object ReturnsHttpSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val gbp = Currency.fromCode("GBP").get

  private final case class Setup(entity: UUID, order: UUID, line: UUID, serial: String, billTo: UUID, agent: UUID)

  // a role holding the given (object, action, viewableLayers) grants; returns its dev kc token id.
  private def principal(xa: HikariTransactor[IO], grants: List[(String, String, List[String])]): IO[String] = {
    val kc = s"ret-${UUID.randomUUID()}"
    (for {
      uid <- AdminRepo.ensureUser(kc, Some("Ret"))
      r   <- AdminRepo.createRole(s"retrole-${UUID.randomUUID()}", Some("returns"))
      _ <- grants.traverse_ {
        case (obj, act, layers) => AdminRepo.addPermission(r, obj, act, None, layers, Nil, "all")
      }
      _ <- AdminRepo.assign(uid, r, Nil, Nil, Nil, Nil, None)
    } yield kc).transact(xa)
  }

  private def setup(xa: HikariTransactor[IO]): IO[Setup] = {
    val serial = s"SER-${UUID.randomUUID()}"
    (for {
      e <-
        sql"INSERT INTO entity (name, jurisdiction, functional_currency, entity_type) VALUES ('E','GB','GBP','operating') RETURNING id"
          .query[UUID]
          .unique
      fam <-
        sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'F') RETURNING id"
          .query[UUID]
          .unique
      v <-
        sql"INSERT INTO product_variant (family_id, sku, generation, is_serialised) VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id"
          .query[UUID]
          .unique
      loc <- InventoryRepo.createLocation(Some(e), "W", "W")
      b <- LotBatchRepo.create(
        NewBatch(
          s"B-${UUID.randomUUID()}",
          None,
          v,
          1,
          BigDecimal("100.00"),
          BigDecimal("1.0"),
          "spot",
          None,
          BigDecimal("0"),
          BigDecimal("0"),
          "GBP"
        ),
        LocalDate.parse("2026-01-01")
      )
      p <-
        sql"INSERT INTO party (display_name, party_type, is_organization) VALUES ('Cust','wholesaler',true) RETURNING id"
          .query[UUID]
          .unique
      o <-
        sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, status, txn_currency, payment_method)
                   VALUES ('ORD-'||nextval('order_no_seq'),'trade',$e,$p,$p,'invoiced','GBP','invoice') RETURNING id"""
          .query[UUID]
          .unique
      line <-
        sql"INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, tax_regime, status) VALUES ($o,$v,1,587.50,'GB_STANDARD','dispatched') RETURNING id"
          .query[UUID]
          .unique
      sid   <- InventoryRepo.addSerial(serial, "v3", v, Some(e), loc)
      _     <- LotBatchRepo.assignSerial(sid, b)
      _     <- sql"UPDATE serial_unit SET status='dispatched', order_line_id=$line WHERE id=$sid".update.run
      agent <- sql"INSERT INTO sales_agent (name) VALUES ('Agent') RETURNING id".query[UUID].unique
      _ <-
        sql"""INSERT INTO commission_entry (agent_id, order_id, basis_amount, rate_applied, amount, currency, kind, status)
                    VALUES ($agent, $o, 375.00, 10, 37.50, 'GBP', 'accrual', 'posted')""".update.run
    } yield Setup(e, o, line, serial, p, agent)).transact(xa)
  }

  private def glAccounts(svc: ReturnService[IO], ledger: TigerBeetleLedger[IO], s: Setup): IO[Unit] =
    ledger.createAccounts(
      List(
        LedgerAccount(svc.arAccount(s.billTo), Ledgers.forCurrency(gbp), LedgerAccountCode.Ar),
        LedgerAccount(svc.revenueAccount(s.entity), Ledgers.forCurrency(gbp), LedgerAccountCode.Revenue),
        LedgerAccount(svc.vatAccount(s.entity, "GB"), Ledgers.forCurrency(gbp), LedgerAccountCode.Vat),
        LedgerAccount(svc.invAccount(s.entity), Ledgers.forCurrency(gbp), LedgerAccountCode.Inv),
        LedgerAccount(svc.cosClearing(s.entity), Ledgers.forCurrency(gbp), LedgerAccountCode.CosClearing),
        LedgerAccount(svc.commPayable(s.agent), Ledgers.forCurrency(gbp), LedgerAccountCode.CommPayable),
        LedgerAccount(svc.commExpense(s.agent), Ledgers.forCurrency(gbp), LedgerAccountCode.CommPayable)
      )
    )

  private def call(
      routes: org.http4s.HttpRoutes[IO],
      method: Method,
      path: String,
      kc: String,
      body: String
  ): IO[(Int, Json)] =
    routes.orNotFound
      .run(
        Request[IO](method, Uri.unsafeFromString(path))
          .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, s"dev:$kc")))
          .withEntity(parseJson(if (body.isEmpty) "{}" else body).toOption.get)
      )
      .flatMap(r => r.bodyText.compile.string.map(b => (r.status.code, parseJson(b).getOrElse(Json.Null))))

  private def env(eventType: String, rmaId: UUID, payload: String): EventEnvelope =
    EventEnvelope(
      UUID.randomUUID().toString,
      eventType,
      1,
      "rma",
      rmaId.toString,
      "k",
      None,
      None,
      None,
      "relay",
      0L,
      payload.getBytes(StandardCharsets.UTF_8)
    )

  private def payloadOf(xa: HikariTransactor[IO], rmaId: UUID, eventType: String): IO[String] =
    sql"SELECT payload::text FROM outbox_event WHERE aggregate_id=$rmaId AND event_type=$eventType ORDER BY occurred_at DESC LIMIT 1"
      .query[String]
      .unique
      .transact(xa)

  test(
    "the full RMA lifecycle drives over HTTP: raise→assess→approve→receive→disposition→refund, money via the consumer"
  ) {
    case (xa, client) =>
      val ledger = TigerBeetleLedger.fromClient[IO](client)
      val routes = new ReturnRoutes[IO](xa, new AuthService[IO](xa, devMode = true)).routes
      val svc    = new ReturnService[IO](xa, ledger)
      for {
        maker <- principal(
          xa,
          List(
            ("rma", "create", List("commercial")),
            ("rma", "edit", List("commercial")),
            ("rma", "approve", List("commercial")),
            ("rma", "view", List("commercial")),
            ("credit_note", "create", Nil)
          )
        )
        checker <- principal(
          xa,
          List(
            ("rma", "approve", List("commercial")),
            ("rma", "edit", List("commercial")),
            ("rma", "view", List("commercial")),
            ("credit_note", "create", Nil)
          )
        )
        s <- setup(xa)
        _ <- glAccounts(svc, ledger, s)
        _ <- ledger.postTransfers(
          List(
            LedgerTransfer(
              TbIds.transferId(UUID.randomUUID(), 0),
              svc.commExpense(s.agent),
              svc.commPayable(s.agent),
              BigInt(3750),
              Ledgers.forCurrency(gbp),
              LedgerTransferCode.Commission
            )
          )
        )
        // raise (maker)
        raiseBody =
          s"""{"type":"full_unit","scope":"serial","reason_code":"changed_mind","lines":[{"order_line_id":"${s.line}","serial":"${s.serial}","qty":1}]}"""
        (raiseCode, raiseJson) <- call(routes, Method.POST, s"/api/v1/orders/${s.order}/returns", maker, raiseBody)
        rmaId = UUID.fromString(raiseJson.hcursor.get[String]("id").toOption.get)
        lineId <- sql"SELECT id FROM rma_line WHERE rma_id=$rmaId".query[UUID].unique.transact(xa)
        // assess (maker)
        (assessCode, _) <- call(
          routes,
          Method.POST,
          s"/api/v1/returns/$rmaId/assess",
          maker,
          s"""{"lines":[{"rma_line_id":"$lineId","condition_grade":"a"}]}"""
        )
        // self-approval by the maker → 403 SoD
        (selfCode, _) <- call(routes, Method.POST, s"/api/v1/returns/$rmaId/approve", maker, "{}")
        // approval by the checker → 200
        (approveCode, _) <- call(routes, Method.POST, s"/api/v1/returns/$rmaId/approve", checker, "{}")
        (receiveCode, _) <- call(routes, Method.POST, s"/api/v1/returns/$rmaId/receive", checker, "{}")
        // disposition (restock) → 202 + command event → replay consumer
        (dispCode, _) <- call(
          routes,
          Method.POST,
          s"/api/v1/returns/$rmaId/disposition",
          checker,
          s"""{"rma_line_id":"$lineId","disposition":"restock"}"""
        )
        dispPayload <- payloadOf(xa, rmaId, "return.disposition_requested")
        dispCmd = ReturnConsumer.dispositionRequested(env("return.disposition_requested", rmaId, dispPayload)).get
        _ <- svc.disposition(dispCmd._1, dispCmd._2, dispCmd._3, dispCmd._4, dispCmd._5)
        // refund → 202 + command event → replay consumer
        (refundCode, _) <-
          call(routes, Method.POST, s"/api/v1/returns/$rmaId/refund", checker, """{"refund_method":"credit_memo"}""")
        refundPayload <- payloadOf(xa, rmaId, "return.refund_requested")
        refCmd = ReturnConsumer.refundRequested(env("return.refund_requested", rmaId, refundPayload)).get
        _      <- svc.refund(refCmd._1, refCmd._2)
        status <- sql"SELECT status FROM rma WHERE id=$rmaId".query[String].unique.transact(xa)
        serSt  <- sql"SELECT status FROM serial_unit WHERE serial_no=${s.serial}".query[String].unique.transact(xa)
        cn     <- sql"SELECT count(*) FROM credit_note WHERE rma_id=$rmaId".query[Long].unique.transact(xa)
        claw <-
          sql"SELECT count(*) FROM commission_entry WHERE order_id=${s.order} AND kind='claw'"
            .query[Long]
            .unique
            .transact(xa)
        invBal <- ledger.balance(svc.invAccount(s.entity))
        arBal  <- ledger.balance(svc.arAccount(s.billTo))
      } yield expect(raiseCode == 201) and expect(assessCode == 200) and
        expect(selfCode == 403) and expect(approveCode == 200) and expect(receiveCode == 200) and
        expect(dispCode == 202) and expect(refundCode == 202) and
        expect(status == "refunded") and expect(serSt == "in_stock") and expect(cn == 1L) and expect(claw == 1L) and
        expect(invBal.debitsPosted == BigInt(10000)) and // INV re-recognised at £100 batch cost
        expect(arBal.creditsPosted == BigInt(70500))     // £587.50 + £117.50 VAT reversed against AR
  }

  test("authz + the data-layer wall: no create:rma is 403; a volume-only viewer sees the RMA but no refund_amount") {
    case (xa, _) =>
      val routes = new ReturnRoutes[IO](xa, new AuthService[IO](xa, devMode = true)).routes
      for {
        noPerm  <- principal(xa, List(("rma", "view", List("commercial")))) // can view, cannot create
        full    <- principal(xa, List(("rma", "create", List("commercial")), ("rma", "view", List("commercial"))))
        volOnly <- principal(xa, List(("rma", "view", List("volume"))))     // no commercial layer
        s       <- setup(xa)
        raiseBody =
          s"""{"type":"full_unit","scope":"serial","reason_code":"changed_mind","lines":[{"order_line_id":"${s.line}","serial":"${s.serial}","qty":1}]}"""
        (forbidden, _)  <- call(routes, Method.POST, s"/api/v1/orders/${s.order}/returns", noPerm, raiseBody)
        (ok, raiseJson) <- call(routes, Method.POST, s"/api/v1/orders/${s.order}/returns", full, raiseBody)
        rmaId = raiseJson.hcursor.get[String]("id").toOption.get
        (commercialView, commJson) <- call(routes, Method.GET, s"/api/v1/returns/$rmaId", full, "")
        (volView, volJson)         <- call(routes, Method.GET, s"/api/v1/returns/$rmaId", volOnly, "")
      } yield expect(forbidden == 403) and expect(ok == 201) and
        expect(commercialView == 200) and expect(commJson.hcursor.downField("refund_amount").succeeded) and
        expect(volView == 200) and expect(volJson.hcursor.downField("refund_amount").failed) and // walled off
        expect(volJson.hcursor.get[String]("rma_no").isRight)                                    // but the RMA itself is visible
  }
}
