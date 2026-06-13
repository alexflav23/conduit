package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all._
import com.hypervolt.conduit.access._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import java.util.UUID
import weaver.IOSuite

object AccessIntegrationSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  test("a UK-wholesale principal's scoped list returns only UK-wholesale rows") { xa =>
    val uk        = UUID.randomUUID()
    val ie        = UUID.randomUUID()
    val wholesale = UUID.randomUUID()
    val retail    = UUID.randomUUID()
    val setup =
      sql"""CREATE TABLE IF NOT EXISTS scope_demo (
              id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
              entity_id uuid, market_id uuid, channel_id uuid, owner_user_id uuid)""".update.run *>
        sql"TRUNCATE scope_demo".update.run *>
        sql"""INSERT INTO scope_demo (market_id, channel_id)
              VALUES ($uk,$wholesale), ($uk,$retail), ($ie,$wholesale)""".update.run
    val principal = Principal(
      UUID.randomUUID(),
      Set.empty,
      List(
        Grant(
          List(Permission("order", Action.View, None, Set(DataLayer.Volume), Set.empty, Breadth.Scoped)),
          Set.empty,
          Set(uk),
          Set(wholesale),
          Set.empty,
          None
        )
      )
    )
    val predicate = ScopePredicate.forPrincipal(principal, "order")
    val count     = (fr"SELECT count(*) FROM scope_demo WHERE" ++ predicate).query[Long].unique
    (setup *> count).transact(xa).map(n => expect(n == 1L))
  }

  test("loadPrincipal assembles permissions, layers and scope; revoking the assignment denies next load") { xa =>
    val kc     = s"user-${UUID.randomUUID()}"
    val market = UUID.randomUUID()
    val prog = for {
      uid    <- sql"INSERT INTO app_user (keycloak_id, name) VALUES ($kc, 'Tester') RETURNING id".query[UUID].unique
      rid    <- sql"INSERT INTO role (name) VALUES (${s"r-$kc"}) RETURNING id".query[UUID].unique
      _      <- sql"""INSERT INTO permission (role_id, object_type, action, viewable_layers, data_breadth)
                 VALUES ($rid, 'order', 'view', '{volume,commercial}', 'scoped')""".update.run
      aid    <- sql"""INSERT INTO role_assignment (user_id, role_id, scope_markets)
                   VALUES ($uid, $rid, ARRAY[$market]::uuid[]) RETURNING id""".query[UUID].unique
      before <- AccessRepo.loadPrincipal(kc)
      _      <- sql"DELETE FROM role_assignment WHERE id = $aid".update.run
      after  <- AccessRepo.loadPrincipal(kc)
    } yield (before, after)
    prog.transact(xa).map {
      case (before, after) =>
        val inScope    = Target(None, Some(market), None, None)
        val grantedNow = before.exists(p => PolicyEngine.authorize(p, Action.View, "order", inScope))
        val layersOk = before.exists(
          _.grants.exists(_.permissions.exists(_.viewableLayers == Set(DataLayer.Volume, DataLayer.Commercial)))
        )
        val scopeOk     = before.exists(_.grants.exists(_.scopeMarkets == Set(market)))
        val deniedAfter = after.forall(p => !PolicyEngine.authorize(p, Action.View, "order", inScope))
        expect(grantedNow) and expect(layersOk) and expect(scopeOk) and expect(deniedAfter)
    }
  }

  test("preset roles and the field-layer map are seeded") { xa =>
    val prog = for {
      roles  <- sql"SELECT count(*) FROM role WHERE is_preset".query[Long].unique
      layers <- sql"SELECT count(*) FROM data_layer".query[Long].unique
      flm <-
        sql"SELECT data_layer FROM field_layer_map WHERE object_type='price_rule' AND field='tp_markup_pct'"
          .query[String]
          .unique
    } yield (roles, layers, flm)
    prog.transact(xa).map {
      case (roles, layers, flm) =>
        expect(roles >= 9L) and expect(layers == 7L) and expect(flm == "inter_entity")
    }
  }
}
