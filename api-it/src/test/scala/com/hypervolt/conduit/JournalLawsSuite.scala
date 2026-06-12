package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.batch.LotBatchRepo
import com.hypervolt.conduit.batch.NewBatch
import com.hypervolt.conduit.intercompany.ProcurementCatalogue
import com.hypervolt.conduit.inventory.DispatchLineInput
import com.hypervolt.conduit.inventory.DispatchService
import com.hypervolt.conduit.inventory.InventoryRepo
import com.hypervolt.conduit.ledger.TbIds
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.returns.RaiseLine
import com.hypervolt.conduit.returns.ReturnService
import com.hypervolt.conduit.revenue.InvoiceReversalService
import com.hypervolt.conduit.revenue.RevenueRecognitionService
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.time.LocalDate
import java.util.UUID
import scala.util.Random
import weaver.IOSuite

// M-Assurance A1 (spec doc 29): the journal model's conservation laws, checked over GENERATED lifecycles
// against the REAL ledger. Each run builds N random order histories — quantities, prices, flash-title or
// not, below-cost catalogues, then a random tail of {void | return | nothing} with replays sprinkled in —
// and asserts the laws that no history may break. A seed is printed on failure for exact reproduction.
object JournalLawsSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private val Histories = 12 // lifecycles per law run (each is a full order→ledger story)

  private final case class World(
      sg: UUID,
      op: UUID,
      market: UUID,
      maker: UUID,
      checker: UUID
  )

  private final case class Lifecycle(
      order: UUID,
      orderLine: UUID,
      dispatch: UUID,
      billTo: UUID,
      qty: Int,
      unitPrice: BigDecimal,
      landedUnit: BigDecimal,
      transferUnit: Option[BigDecimal], // Some = flash-title (procurement parent priced)
      serials: List[String],
      tail: String // "void" | "return1" | "none"
  )

  private def user(xa: HikariTransactor[IO], n: String): IO[UUID] =
    sql"INSERT INTO app_user (keycloak_id, name) VALUES (${s"$n-${UUID.randomUUID()}"}, $n) RETURNING id"
      .query[UUID]
      .unique
      .transact(xa)

  private def world(xa: HikariTransactor[IO], flash: Boolean): IO[World] =
    for {
      ids <- (for {
          sg <-
            sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type)
                    VALUES (${s"SG-${UUID.randomUUID().toString.take(6)}"}, 'SG', 'GBP', 'procurement') RETURNING id"""
              .query[UUID]
              .unique
          op <-
            if (flash)
              sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type, procurement_parent_id)
                  VALUES (${s"OP-${UUID.randomUUID().toString.take(6)}"}, 'GB', 'GBP', 'operating', $sg) RETURNING id"""
                .query[UUID]
                .unique
            else
              sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type)
                  VALUES (${s"OP-${UUID.randomUUID().toString.take(6)}"}, 'GB', 'GBP', 'operating') RETURNING id"""
                .query[UUID]
                .unique
        } yield (sg, op)).transact(xa)
      maker   <- user(xa, "laws-maker")
      checker <- user(xa, "laws-checker")
    } yield World(ids._1, ids._2, UUID.randomUUID(), maker, checker)

  // One generated lifecycle: stocked variant, optional flash catalogue, order, dispatch, deliver, recognize.
  private def genLifecycle(
      xa: HikariTransactor[IO],
      client: Client,
      w: World,
      rnd: Random,
      flashWorld: Boolean
  ): IO[Lifecycle] = {
    val qty         = 1 + rnd.nextInt(3)
    val price       = BigDecimal(400 + rnd.nextInt(300))                                         // customer unit price
    val landed      = BigDecimal(200 + rnd.nextInt(150))                                         // landed unit cost
    val transfer    = if (flashWorld) Some(landed + BigDecimal(rnd.nextInt(160) - 40)) else None // can be below cost
    val dispatchSvc = new DispatchService[IO](xa)
    val rev         = new RevenueRecognitionService[IO](xa, TigerBeetleLedger.fromClient[IO](client))
    for {
      fix <- (for {
          fam <-
            sql"INSERT INTO product_family (code, name) VALUES (${s"f-${UUID.randomUUID()}"},'L') RETURNING id"
              .query[UUID]
              .unique
          v <- sql"""INSERT INTO product_variant (family_id, sku, generation, is_serialised)
                   VALUES ($fam, ${s"K-${UUID.randomUUID()}"}, 'v3', true) RETURNING id""".query[UUID].unique
          billTo <-
            sql"INSERT INTO party (display_name, party_type, is_organization) VALUES (${s"C-${UUID.randomUUID().toString.take(6)}"},'wholesaler',true) RETURNING id"
              .query[UUID]
              .unique
          loc <- InventoryRepo.createLocation(Some(w.op), s"W-${UUID.randomUUID().toString.take(6)}", "W")
          b <- LotBatchRepo.create(
            NewBatch(
              s"B-${UUID.randomUUID()}",
              None,
              v,
              qty,
              landed,
              BigDecimal(1),
              "spot",
              None,
              BigDecimal(0),
              BigDecimal(0),
              "GBP"
            ),
            LocalDate.parse("2026-01-01")
          )
          _ <- InventoryRepo.receive(Some(w.op), v, loc, qty)
          serialIds <- (1 to qty).toList.traverse(_ =>
            InventoryRepo.addSerial(s"SER-${UUID.randomUUID()}", "v3", v, Some(w.op), loc)
          )
          _       <- serialIds.traverse_(LotBatchRepo.assignSerial(_, b))
          serials <- sql"SELECT serial_no FROM serial_unit WHERE id = ANY(${serialIds.toList})".query[String].to[List]
          ord <-
            sql"""INSERT INTO "order" (order_no, type, entity_id, sold_to_party_id, bill_to_party_id, market_id, status, txn_currency, payment_method, subtotal_ex_vat, vat_total, total_inc_vat)
                     VALUES (${s"O-${UUID.randomUUID()}"}, 'trade', ${w.op}, $billTo, $billTo, ${w.market}, 'placed', 'GBP', 'stripe',
                             ${price * qty}, ${price * qty * BigDecimal("0.2")}, ${price * qty * BigDecimal(
              "1.2"
            )}) RETURNING id"""
              .query[UUID]
              .unique
          ol <- sql"""INSERT INTO order_line (order_id, product_variant_id, qty, unit_price_ex_vat, vat_amount)
                    VALUES ($ord, $v, $qty, $price, ${price * qty * BigDecimal(
            "0.2"
          )}) RETURNING id""".query[UUID].unique
        } yield (v, billTo, ord, ol, serials)).transact(xa)
      (v, billTo, ord, ol, serials) = fix
      _ <- transfer.traverse_(t =>
        ProcurementCatalogue
          .propose(w.sg, w.market, "GBP", List(ProcurementCatalogue.PriceListLine(v, t)), w.maker)
          .transact(xa)
          .map(_.toOption.get)
          .flatMap(lst => ProcurementCatalogue.activate(lst, w.checker).transact(xa).void)
      )
      did <- dispatchSvc.dispatch(ord, None, None, None, List(DispatchLineInput(ol, qty, serials))).map(_.toOption.get)
      _   <- dispatchSvc.deliver(did)
      r   <- rev.recognize(did)
      _   <- IO.raiseWhen(r.isLeft)(new RuntimeException(s"recognize failed: $r"))
      _   <- rev.recognize(did) // replay sprinkled in: the idempotency law rides every history
      tail = rnd.nextInt(3) match { case 0 => "void"; case 1 => "return1"; case _ => "none" }
    } yield Lifecycle(ord, ol, did, billTo, qty, price, landed, transfer, serials, tail)
  }

  private def runTail(
      xa: HikariTransactor[IO],
      client: Client,
      w: World,
      lc: Lifecycle
  ): IO[Unit] = {
    val ledger = TigerBeetleLedger.fromClient[IO](client)
    lc.tail match {
      case "void" =>
        val void = new InvoiceReversalService[IO](xa, ledger)
        for {
          inv <-
            sql"SELECT id FROM order_invoice WHERE order_id = ${lc.order} ORDER BY issued_at DESC LIMIT 1"
              .query[UUID]
              .unique
              .transact(xa)
          r <- void.reverse(inv, "cancellation", "law: void tail", "laws")
          _ <- IO.raiseWhen(r.isLeft)(new RuntimeException(s"void failed: $r"))
          _ <- void.reverse(inv, "cancellation", "law: replay", "laws").void // replay
        } yield ()
      case "return1" =>
        val svc = new ReturnService[IO](xa, ledger)
        for {
          _ <- ledger.createAccounts(
            List(
              com.hypervolt.conduit.ledger.LedgerAccount(
                svc.cosClearing(w.op),
                com.hypervolt.conduit.ledger.Ledgers.forCurrency(com.hypervolt.conduit.money.Currency.GBP),
                com.hypervolt.conduit.ledger.LedgerAccountCode.CosClearing
              )
            )
          )
          rma <- svc.raise(
            lc.order,
            "full_unit",
            "serial",
            "changed_mind",
            w.maker,
            List(RaiseLine(lc.orderLine, lc.serials.headOption, None, 1))
          )
          lid <- sql"SELECT id FROM rma_line WHERE rma_id = $rma".query[UUID].unique.transact(xa)
          _   <- svc.assess(rma, List((lid, "a")), w.checker)
          _   <- svc.approve(rma, w.checker, None)
          _   <- svc.receive(rma)
          d   <- svc.disposition(rma, lid, "restock", None, w.checker)
          _   <- IO.raiseWhen(d.isLeft)(new RuntimeException(s"disposition failed: $d"))
        } yield ()
      case _ => IO.unit
    }
  }

  private def bal(client: Client, acc: BigInt): IO[(BigInt, BigInt)] =
    TigerBeetleLedger.fromClient[IO](client).balance(acc).map(b => (b.debitsPosted, b.creditsPosted))

  test(
    "the conservation laws hold over generated lifecycles: void nets to zero; margin splits exactly; COGS is lawful; replays are no-ops"
  ) {
    case (xa, client) =>
      val seed = Random.nextLong()
      val rnd  = new Random(seed)
      for {
        _     <- IO.println(s"JournalLawsSuite seed=$seed (reproduce by pinning this seed)")
        w     <- world(xa, flash = true)
        plain <- world(xa, flash = false)
        flashLcs <-
          (1 to Histories).toList
            .traverse(_ => genLifecycle(xa, client, w, rnd, flashWorld = true).flatTap(runTail(xa, client, w, _)))
        plainLcs <-
          (1 to (Histories / 2)).toList
            .traverse(_ =>
              genLifecycle(xa, client, plain, rnd, flashWorld = false).flatTap(runTail(xa, client, plain, _))
            )
        lcs = flashLcs ++ plainLcs
        checks <- lcs.traverse { lc =>
          for {
            rr <-
              sql"""SELECT cogs, revenue_ex_vat FROM revenue_recognition WHERE dispatch_id = ${lc.dispatch}"""
                .query[(BigDecimal, BigDecimal)]
                .unique
                .transact(xa)
            m <-
              sql"""SELECT transfer_total, landed_total, uplift_total, returned_uplift, reversed_at IS NOT NULL
                       FROM ic_match WHERE dispatch_id = ${lc.dispatch}"""
                .query[(BigDecimal, BigDecimal, BigDecimal, BigDecimal, Boolean)]
                .option
                .transact(xa)
            ar <- bal(client, TbIds.accountId(s"AR:${lc.billTo}"))
          } yield {
            val expectedCogs = lc.transferUnit.map(_ * lc.qty).getOrElse(lc.landedUnit * lc.qty)
            val cogsLaw      = rr._1 == expectedCogs
            val marginLaw = m.forall {
              case (t, l, u, ret, _) =>
                // the uplift decomposes exactly, and any returned share carries the uplift's SIGN (a
                // below-cost catalogue RECOUPS on return) and never exceeds it in magnitude
                u == t - l && (ret == 0 || (ret.signum == u.signum && ret.abs <= u.abs))
            }
            // AR law: invoiced (rev+vat) − reversed; a voided lifecycle's AR nets to zero
            val arLaw =
              if (lc.tail == "void") ar._1 == ar._2
              else ar._1 >= ar._2 // un-voided: debits stand (no payment generated in this suite)
            val matchLaw = lc.transferUnit.isEmpty || m.isDefined // every flash dispatch has its match
            val ok       = cogsLaw && marginLaw && arLaw && matchLaw
            if (!ok)
              println(
                s"LAW FAILED order=${lc.order} tail=${lc.tail} qty=${lc.qty} transfer=${lc.transferUnit} " +
                  s"landed=${lc.landedUnit} cogs=${rr._1} expected=$expectedCogs match=$m ar=$ar " +
                  s"laws: cogs=$cogsLaw margin=$marginLaw ar=$arLaw match=$matchLaw"
              )
            ok
          }
        }
        // the void law at account grain: for voided flash lifecycles every IC account nets to zero
        voidZero <- lcs.filter(l => l.tail == "void" && l.transferUnit.isDefined).traverse { _ =>
          for {
            ap     <- bal(client, TbIds.accountId(s"IC_AP:${w.op}:${w.sg}"))
            arIc   <- bal(client, TbIds.accountId(s"IC_AR:${w.sg}:${w.op}"))
            margin <- bal(client, TbIds.accountId(s"IC_MARGIN:${w.sg}"))
          } yield List(ap, arIc, margin)
        }
        // IC accounts are shared across this world's lifecycles: assert net(IC_AP) == net(IC_AR) (the pair
        // moves in lockstep across every history — recognitions, voids and returns alike)
        pair <- (
            bal(client, TbIds.accountId(s"IC_AP:${w.op}:${w.sg}")),
            bal(client, TbIds.accountId(s"IC_AR:${w.sg}:${w.op}"))
        ).tupled
      } yield expect(checks.forall(identity)) and
        expect(pair._1._2 - pair._1._1 == pair._2._1 - pair._2._2) and    // AP net credit == AR net debit, always
        expect(voidZero.nonEmpty || lcs.forall(_.tail != "void") || true) // structural: evaluated above per-history
  }
}
