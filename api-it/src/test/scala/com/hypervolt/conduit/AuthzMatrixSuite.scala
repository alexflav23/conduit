package com.hypervolt.conduit

import cats.effect.IO
import cats.effect.Resource
import com.hypervolt.conduit.access.Action
import com.hypervolt.conduit.access.Breadth
import com.hypervolt.conduit.access.DataLayer
import com.hypervolt.conduit.access.FieldLayerMap
import com.hypervolt.conduit.access.Grant
import com.hypervolt.conduit.access.Permission
import com.hypervolt.conduit.access.Principal
import com.hypervolt.conduit.access.Projection
import com.hypervolt.conduit.access.ScopeColumns
import com.hypervolt.conduit.access.ScopePredicate
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.hikari.HikariTransactor
import io.circe.Json
import io.circe.syntax._
import java.util.UUID
import weaver.IOSuite

// M-Assurance slice B (spec doc 29 B): the authorization matrix, GENERATED from the permission seeds +
// FieldLayerMap — so two bug classes become structurally unreintroducible:
//  (1) the WALL LEAK — a field served without its data layer (the projection matrix below proves absence
//      for EVERY (object, field) in the seed, both directions);
//  (2) CHECKER-CANT-SEE-WHAT-THEY-APPROVE (the V1_0_61 class) — a role that can approve/act on an object
//      but cannot view it, or can edit a layer it cannot see.
// Plus a sector-scope filter proof, tying the new sector axis (doc 05 §2) to a real query.
object AuthzMatrixSuite extends IOSuite {

  override type Res = HikariTransactor[IO]
  override def maxParallelism: Int               = 1
  override def sharedResource: Resource[IO, Res] = TestPostgres.transactor

  // A principal holding exactly one view-grant on `obj` with `layers` — the minimal probe for the wall.
  private def viewer(obj: String, layers: Set[DataLayer]): Principal =
    Principal(
      UUID.randomUUID(),
      Set.empty,
      List(
        Grant(
          List(Permission(obj, Action.View, None, layers, Set.empty, Breadth.All)),
          Set.empty,
          Set.empty,
          Set.empty,
          Set.empty,
          None
        )
      )
    )

  // ----- (1) the wall matrix: every classified field, both directions -----

  test("the wall holds for EVERY (object, field) in FieldLayerMap: absent without the layer, present with it") { _ =>
    val results = FieldLayerMap.seed.toList.map {
      case ((obj, field), layer) =>
        val row      = Json.obj(field -> "x".asJson, "always_visible" -> 1.asJson)
        val without  = Projection.projectFor(viewer(obj, DataLayer.all.toSet - layer), obj, row)
        val withIt   = Projection.projectFor(viewer(obj, Set(layer)), obj, row)
        val hiddenOk = without.hcursor.downField(field).failed               // ABSENT, not null (doc 05 §3)
        val shownOk  = withIt.hcursor.downField(field).succeeded
        val unclOk   = without.hcursor.downField("always_visible").succeeded // unclassified always visible
        (s"$obj.$field", hiddenOk && shownOk && unclOk)
    }
    val broken = results.filterNot(_._2).map(_._1)
    IO.pure(expect(broken.isEmpty, "wall leaks at: " + broken.mkString(", ")) and expect(results.size >= 30))
  }

  // ----- (2) approve/act ⇒ view, and edit ⊆ view, over the live permission seeds -----

  private def rolePerms(xa: HikariTransactor[IO]): IO[List[(String, String, String, List[String], List[String])]] =
    sql"""SELECT r.name, p.object_type, p.action, p.viewable_layers, p.editable_layers
          FROM permission p JOIN role r ON r.id = p.role_id
          WHERE r.is_preset"""
      .query[(String, String, String, List[String], List[String])]
      .to[List]
      .transact(xa)

  test("no preset role can act on an object it cannot view (the checker-cant-see-what-they-approve class)") { xa =>
    rolePerms(xa).map { rows =>
      val viewable = rows.filter(_._3 == "view").map(r => (r._1, r._2)).toSet
      // every non-view capability (approve/edit/create/delete/export) must be backed by a view on the same object
      val blind = rows
        .filterNot(r => r._3 == "view")
        .map(r => (r._1, r._2, r._3))
        .filterNot { case (role, obj, _) => viewable.contains((role, obj)) }
        .distinct
      expect(blind.isEmpty, "act-without-view at: " + blind.mkString(", "))
    }
  }

  test("no preset role can edit a data layer it cannot view (edit ⊆ view per role×object)") { xa =>
    rolePerms(xa).map { rows =>
      val viewableLayers = rows
        .filter(_._3 == "view")
        .groupBy(r => (r._1, r._2))
        .map { case (k, rs) => k -> rs.flatMap(_._4).toSet }
      val leaks = rows.flatMap { r =>
        val editable = r._5.toSet
        val seen     = viewableLayers.getOrElse((r._1, r._2), Set.empty)
        val over     = editable.diff(seen)
        if (over.nonEmpty) List((r._1, r._2, r._3, over.mkString("|"))) else Nil
      }.distinct
      expect(leaks.isEmpty, "editable-but-not-viewable at: " + leaks.mkString(", "))
    }
  }

  // ----- the CEO's specific approval surfaces (the regressions that motivated the law) -----

  test("the CEO can VIEW everything it approves — tax rates and transfer-price policies included") { xa =>
    rolePerms(xa).map { rows =>
      val ceoApprove = rows.filter(r => r._1 == "ceo" && r._3 == "approve").map(_._2).toSet
      val ceoView    = rows.filter(r => r._1 == "ceo" && r._3 == "view").map(_._2).toSet
      val blind      = ceoApprove.diff(ceoView)
      expect(blind.isEmpty, "ceo approves blind on: " + blind.mkString(", ")) and expect(ceoApprove.nonEmpty)
    }
  }

  // ----- the sector scope axis as a real query filter (doc 05 §2) -----

  test("the sector axis filters a list: an energy-scoped grant sees only energy rows") { xa =>
    val energyGrant = Principal(
      UUID.randomUUID(),
      Set.empty,
      List(
        Grant(
          List(Permission("party", Action.View, None, Set(DataLayer.Volume), Set.empty, Breadth.Scoped)),
          Set.empty,
          Set.empty,
          Set.empty,
          Set("energy"),
          None
        )
      )
    )
    val cols = ScopeColumns.default.copy(sector = fr"sector")
    val pred = ScopePredicate.forPrincipal(energyGrant, "party", cols)
    // seed two sector-tagged parties; the predicate must return only the energy one
    (for {
      e <-
        sql"INSERT INTO party (display_name, party_type, is_organization, sector) VALUES ('Energy Co','wholesaler',true,'energy') RETURNING id"
          .query[UUID]
          .unique
      i <-
        sql"INSERT INTO party (display_name, party_type, is_organization, sector) VALUES ('Installer Co','wholesaler',true,'installers') RETURNING id"
          .query[UUID]
          .unique
      visible <- (fr"SELECT id FROM party WHERE id IN ($e, $i) AND (" ++ pred ++ fr")").query[UUID].to[List]
    } yield expect(visible.contains(e) && !visible.contains(i))).transact(xa)
  }

  // an UNSCOPED-sector grant still sees every sector (empty axis = unconstrained)
  test("an empty sector axis is unconstrained: the grant sees all sectors") { xa =>
    val anyGrant = Principal(
      UUID.randomUUID(),
      Set.empty,
      List(
        Grant(
          List(Permission("party", Action.View, None, Set(DataLayer.Volume), Set.empty, Breadth.Scoped)),
          Set.empty,
          Set.empty,
          Set.empty,
          Set.empty,
          None
        )
      )
    )
    val cols = ScopeColumns.default.copy(sector = fr"sector")
    val pred = ScopePredicate.forPrincipal(anyGrant, "party", cols)
    (for {
      a <-
        sql"INSERT INTO party (display_name, party_type, is_organization, sector) VALUES ('Any A','wholesaler',true,'energy') RETURNING id"
          .query[UUID]
          .unique
      b <-
        sql"INSERT INTO party (display_name, party_type, is_organization, sector) VALUES ('Any B','wholesaler',true,'retail') RETURNING id"
          .query[UUID]
          .unique
      visible <- (fr"SELECT id FROM party WHERE id IN ($a, $b) AND (" ++ pred ++ fr")").query[UUID].to[List]
    } yield expect(visible.contains(a) && visible.contains(b))).transact(xa)
  }
}
