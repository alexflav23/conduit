package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes.EntityStructureRoutes
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import org.http4s._
import org.http4s.implicits._
import java.util.UUID
import weaver.IOSuite

// doc 28 §2.4 — one endpoint, two truths. A finance viewer (no inter_entity) gets the legal tree where the
// procurement entity and its edges DO NOT EXIST; a procurement/admin viewer gets the full principal/LRD
// topology; a role without view:entity_structure gets 403. Absence, never redaction marks (doc 05).
object EntityStructureSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  private def seedUser(xa: HikariTransactor[IO], kc: String, role: String): IO[Unit] =
    (for {
      _ <-
        sql"INSERT INTO app_user (keycloak_id, name) VALUES ($kc, $kc) ON CONFLICT (keycloak_id) DO NOTHING".update.run
      _ <- sql"""INSERT INTO role_assignment (user_id, role_id)
                 SELECT u.id, r.id FROM app_user u, role r WHERE u.keycloak_id = $kc AND r.name = $role
                 AND NOT EXISTS (SELECT 1 FROM role_assignment ra WHERE ra.user_id = u.id AND ra.role_id = r.id)""".update.run
    } yield ()).transact(xa)

  private def seedStructure(xa: HikariTransactor[IO]): IO[(UUID, UUID)] =
    (for {
      sg <-
        sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type)
                  VALUES (${s"SG-Principal-${UUID.randomUUID().toString.take(6)}"}, 'SG', 'GBP', 'procurement') RETURNING id"""
          .query[UUID]
          .unique
      op <-
        sql"""INSERT INTO entity (name, jurisdiction, functional_currency, entity_type, procurement_parent_id)
                  VALUES (${s"UK-Op-${UUID.randomUUID().toString.take(6)}"}, 'GB', 'GBP', 'operating', $sg) RETURNING id"""
          .query[UUID]
          .unique
    } yield (sg, op)).transact(xa)

  private def get(xa: HikariTransactor[IO], token: String): IO[(Status, String)] = {
    val app = new EntityStructureRoutes[IO](xa, new AuthService[IO](xa, devMode = true)).routes.orNotFound
    app
      .run(
        Request[IO](Method.GET, uri"/api/v1/group/structure")
          .withHeaders(Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $token"))
      )
      .flatMap(r => r.as[String].map((r.status, _)))
  }

  test("the walled viewer sees a tree in which the procurement layer does not exist") { xa =>
    for {
      ids <- seedStructure(xa)
      (sg, op) = ids
      _   <- seedUser(xa, "fin-struct", "finance")
      res <- get(xa, "dev:fin-struct")
      (status, body) = res
    } yield expect(status == Status.Ok) and
      expect(body.contains(op.toString)) and          // the operating entity is there
      expect(!body.contains(sg.toString)) and         // the principal is NOT — the row is absent
      expect(!body.contains("procurement_parent_id")) // the edge field does not exist, not nulled
  }

  test("a procurement viewer sees the full principal/LRD topology") { xa =>
    for {
      ids <- seedStructure(xa)
      (sg, op) = ids
      _   <- seedUser(xa, "sg-struct", "procurement")
      res <- get(xa, "dev:sg-struct")
      (status, body) = res
    } yield expect(status == Status.Ok) and
      expect(body.contains(sg.toString)) and
      expect(body.contains(op.toString)) and
      expect(body.contains("procurement_parent_id"))
  }

  test("without view:entity_structure the org chart 403s") { xa =>
    for {
      _   <- seedUser(xa, "agent-struct", "fulfilment_agent")
      res <- get(xa, "dev:agent-struct")
    } yield expect(res._1 == Status.Forbidden)
  }
}
