package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.ProofRoutes
import com.hypervolt.conduit.demo.DemoBook
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.tigerbeetle.Client
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import java.util.UUID
import org.http4s._
import org.typelevel.ci.CIString
import weaver.IOSuite

// M-Proof P2 (spec doc 31 §3): the Proof Center surface over the seeded demo book. The laws register carries
// live last-runs; controls re-perform on demand; the trial balance ties; the ASC-606 bundle serves two truths
// (the flash overlay is ABSENT without inter_entity); the Tamper Sandbox is double-gated — manage permission
// AND a non-prod deployment (in prod the endpoints do not exist).
object ProofRoutesSuite extends IOSuite {

  override type Res = (HikariTransactor[IO], Client)
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = (TestPostgres.transactor, TestTigerBeetle.client).tupled

  private def seedUser(xa: HikariTransactor[IO], kc: String, role: String): IO[Unit] =
    (for {
      _ <-
        sql"INSERT INTO app_user (keycloak_id, name) VALUES ($kc, $kc) ON CONFLICT (keycloak_id) DO NOTHING".update.run
      _ <- sql"""INSERT INTO role_assignment (user_id, role_id)
                 SELECT u.id, r.id FROM app_user u, role r WHERE u.keycloak_id = $kc AND r.name = $role
                 AND NOT EXISTS (SELECT 1 FROM role_assignment ra WHERE ra.user_id = u.id AND ra.role_id = r.id)""".update.run
    } yield ()).transact(xa)

  private def call(
      xa: HikariTransactor[IO],
      token: String,
      method: Method,
      path: String,
      tamperEnabled: Boolean = true
  ): IO[(Status, Json)] = {
    val app = new ProofRoutes[IO](xa, new AuthService[IO](xa, devMode = true), tamperEnabled).routes.orNotFound
    app
      .run(
        Request[IO](method, Uri.unsafeFromString(path))
          .withHeaders(Header.Raw(CIString("Authorization"), s"Bearer $token"))
      )
      .flatMap(r => r.as[String].map(b => (r.status, io.circe.parser.parse(b).getOrElse(Json.Null))))
  }

  private def demoOrder(xa: HikariTransactor[IO], po: String): IO[UUID] =
    sql"""SELECT id FROM "order" WHERE customer_po_number = $po""".query[UUID].unique.transact(xa)

  test("the demo book seeds once; the laws register serves 14 laws with live last-runs on control pins") {
    case (xa, client) =>
      for {
        _ <- DemoBook.seed(xa, TigerBeetleLedger.fromClient[IO](client))
        _ <- List(("proof-fin", "finance"), ("proof-admin", "admin"), ("proof-agent", "fulfilment_agent")).traverse_ {
          case (kc, role) => seedUser(xa, kc, role)
        }
        res <- call(xa, "dev:proof-fin", Method.GET, "/api/v1/proof/laws")
        (status, body) = res
        laws           = body.hcursor.downField("laws").as[List[Json]].getOrElse(Nil)
        lineagePin =
          laws
            .find(_.hcursor.get[String]("id").contains("L13"))
            .flatMap(_.hcursor.downField("pins").as[List[Json]].toOption)
            .flatMap(_.find(_.hcursor.get[String]("ref").contains("CTRL-LINEAGE-CLOSURE")))
      } yield expect(status == Status.Ok) and
        expect.same(laws.size, 14) and
        expect(
          lineagePin.exists(_.hcursor.get[String]("last_result").contains("pass"))
        ) // the verifier's run is the evidence
  }

  test("a control re-performs on demand and leaves a control_run row") {
    case (xa, _) =>
      for {
        before <- sql"SELECT count(*) FROM control_run".query[Long].unique.transact(xa)
        res    <- call(xa, "dev:proof-fin", Method.POST, "/api/v1/proof/controls/CTRL-LINEAGE-CLOSURE/run")
        after  <- sql"SELECT count(*) FROM control_run".query[Long].unique.transact(xa)
      } yield expect(res._1 == Status.Ok) and
        expect(res._2.hcursor.get[String]("result").contains("pass")) and
        expect(after == before + 1) // green is earned per click and leaves audit evidence
  }

  test("the trial balance ties: total debits == total credits on the demo book") {
    case (xa, _) =>
      for {
        op  <- sql"SELECT id FROM entity WHERE name = 'Hypervolt UK (demo)'".query[UUID].unique.transact(xa)
        res <- call(xa, "dev:proof-fin", Method.GET, s"/api/v1/proof/trial-balance/$op")
      } yield expect(res._1 == Status.Ok) and
        expect(res._2.hcursor.get[Boolean]("balanced").contains(true))
  }

  test(
    "ASC 606 serves two truths: finance gets five steps with NO flash overlay; admin gets the principal/LRD decomposition"
  ) {
    case (xa, _) =>
      for {
        oid   <- demoOrder(xa, "PO-AUR-0001")
        fin   <- call(xa, "dev:proof-fin", Method.GET, s"/api/v1/proof/asc606/$oid")
        admin <- call(xa, "dev:proof-admin", Method.GET, s"/api/v1/proof/asc606/$oid")
        steps = List(
          "step1_identify_contract",
          "step2_performance_obligations",
          "step3_transaction_price",
          "step4_allocation",
          "step5_recognition"
        )
      } yield expect(fin._1 == Status.Ok) and
        expect(steps.forall(s => fin._2.hcursor.downField(s).succeeded)) and
        expect(
          fin._2.hcursor
            .downField("step1_identify_contract")
            .downField("price_agreements")
            .focus
            .exists(_.asArray.exists(_.nonEmpty))
        ) and
        expect(
          fin._2.hcursor
            .get[Json]("step3_transaction_price")
            .toOption
            .exists(!_.hcursor.downField("rebate").focus.contains(Json.Null))
        ) and
        expect(fin._2.hcursor.downField("step5_recognition_flash").failed) and  // ABSENT, not null (L7)
        expect(admin._2.hcursor.downField("step5_recognition_flash").succeeded) // the overlay exists for the holder
  }

  test("the wall: a fulfilment agent gets 403 on every proof surface") {
    case (xa, _) =>
      for {
        l <- call(xa, "dev:proof-agent", Method.GET, "/api/v1/proof/laws")
        c <- call(xa, "dev:proof-agent", Method.POST, "/api/v1/proof/controls/CTRL-GL-MIRROR/run")
      } yield expect(l._1 == Status.Forbidden) and expect(c._1 == Status.Forbidden)
  }

  test(
    "the tamper sandbox: corrupt -> the control names it -> restore -> green; finance cannot; prod = the endpoint does not exist"
  ) {
    case (xa, _) =>
      for {
        t      <- call(xa, "dev:proof-admin", Method.POST, "/api/v1/proof/tamper/delete_leg")
        broken <- call(xa, "dev:proof-admin", Method.POST, "/api/v1/proof/controls/CTRL-LINEAGE-CLOSURE/run")
        named <-
          sql"SELECT count(*) FROM lineage_closure_violation WHERE kind = 'missing_leg'".query[Long].unique.transact(xa)
        r          <- call(xa, "dev:proof-admin", Method.POST, "/api/v1/proof/tamper-restore")
        restored   <- call(xa, "dev:proof-admin", Method.POST, "/api/v1/proof/controls/CTRL-LINEAGE-CLOSURE/run")
        finDenied  <- call(xa, "dev:proof-fin", Method.POST, "/api/v1/proof/tamper/delete_leg")
        prodAbsent <- call(xa, "dev:proof-admin", Method.POST, "/api/v1/proof/tamper/delete_leg", tamperEnabled = false)
      } yield expect(t._1 == Status.Ok) and
        expect(broken._2.hcursor.get[String]("result").contains("fail")) and
        expect(named > 0L) and
        expect(r._1 == Status.Ok) and
        expect(restored._2.hcursor.get[String]("result").contains("pass")) and
        expect(finDenied._1 == Status.Forbidden) and
        expect(prodAbsent._1 == Status.NotFound)
  }
}
