package com.hypervolt.conduit.ingest

import cats.effect.IO
import cats.effect.Resource
import io.circe.Json
import io.circe.parser.{parse => parseJson}
import org.http4s.Response
import org.http4s.Status
import org.http4s.circe._
import org.http4s.client.Client
import weaver.SimpleIOSuite

// S2.1 (spec 37 §1): the live HubSpot API normalizes a CRM-v3 page {id, properties:{…}} into the canonical record
// the shared SnapshotLoader handler consumes (boot-ndjson shape) — while keeping `id` (the connector's sourceId)
// and properties.hs_lastmodifieddate (the connector's watermark) so the connector + boot mapping path are
// unchanged. Fixture-driven against a fake http client (no live token).
object HttpHubSpotApiSuite extends SimpleIOSuite {

  private def fakeClient(body: Json): Client[IO] =
    Client[IO](_ => Resource.pure[IO, Response[IO]](Response[IO](Status.Ok).withEntity(body)))

  private val companiesV3 = parseJson("""
    {"results":[
      {"id":"5315083302","properties":{"name":"HeatForce Wales Ltd","domain":"heatforce.co.uk",
        "industry":"CONSTRUCTION","country":"United Kingdom","hs_lastmodifieddate":"1717238400000"}}
    ],"paging":{"next":{"after":"2"}}}""").toOption.get

  private val contactsV3 = parseJson("""
    {"results":[
      {"id":"101","properties":{"email":"matt@severnsparks.co.uk","firstname":"Matt","lastname":"Yates",
        "phone":"01452447030","jobtitle":"MD","lifecyclestage":"customer","createdate":"2021-02-07T10:00:00.000Z",
        "associatedcompanyid":"5315090897","hs_lastmodifieddate":"1717238400000"}}
    ]}""").toOption.get

  test("companies: v3 → canonical (company_id/name/domain), keeps id + watermark") {
    new HttpHubSpotApi[IO](fakeClient(companiesV3), "tok", "https://api.hubapi.com").get("companies", None).map { out =>
      val r = out.hcursor.downField("results").downN(0)
      expect(r.get[String]("id").toOption.contains("5315083302")) and
        expect(r.get[String]("company_id").toOption.contains("5315083302")) and
        expect(r.get[String]("name").toOption.contains("HeatForce Wales Ltd")) and
        expect(r.get[String]("domain").toOption.contains("heatforce.co.uk")) and
        expect(r.downField("properties").get[String]("hs_lastmodifieddate").toOption.contains("1717238400000"))
    }
  }

  test("contacts: v3 property names remap (firstname→first_name, createdate→date) + association as company_id") {
    new HttpHubSpotApi[IO](fakeClient(contactsV3), "tok", "https://api.hubapi.com").get("contacts", None).map { out =>
      val r = out.hcursor.downField("results").downN(0)
      expect(r.get[String]("contact_id").toOption.contains("101")) and
        expect(r.get[String]("first_name").toOption.contains("Matt")) and
        expect(r.get[String]("last_name").toOption.contains("Yates")) and
        expect(r.get[String]("lifecycle").toOption.contains("customer")) and
        expect(r.get[String]("created").toOption.contains("2021-02-07")) and
        expect(r.get[String]("company_id").toOption.contains("5315090897"))
    }
  }

  test("the connector consumes the normalized page: keys on id, advances the watermark") {
    val conn = new HubSpotConnector[IO](new HttpHubSpotApi[IO](fakeClient(companiesV3), "tok", "https://api.hubapi.com"))
    conn.pullSince("companies", None).map { batch =>
      expect(batch.records.map(_.sourceId) == List("5315083302")) and
        expect(batch.records.head.payload.hcursor.get[String]("company_id").toOption.contains("5315083302")) and
        expect(batch.nextCursor.contains(SyncCursor("1717238400000")))
    }
  }

  // A path-routing fake: deals normalization makes 3 calls (search, pipelines, batch-associations).
  private def routingClient(byPath: (String => Json)): Client[IO] =
    Client[IO](req => Resource.pure[IO, Response[IO]](Response[IO](Status.Ok).withEntity(byPath(req.uri.path.renderString))))

  private val dealSearch = parseJson("""
    {"results":[{"id":"4111256677","properties":{"dealname":"Greenleaf EV","amount":"798","pipeline":"default",
      "createdate":"2021-02-07T10:00:00.000Z","hs_is_closed_won":"false","hs_is_closed":"false",
      "hs_lastmodifieddate":"1717238400000"}}]}""").toOption.get
  private val pipelinesBody = parseJson("""{"results":[{"id":"default","label":"UK Installers"}]}""").toOption.get
  private val assocBody = parseJson("""
    {"results":[{"from":{"id":"4111256677"},"to":[{"toObjectId":5315090904,
      "associationTypes":[{"label":"Primary"}]}]}]}""").toOption.get

  test("deals: pipeline id→label, v4 company attribution, segment — the deal_snapshot canonical shape") {
    val client = routingClient(p =>
      if (p.contains("/pipelines/")) pipelinesBody
      else if (p.contains("/associations/")) assocBody
      else dealSearch
    )
    new HttpHubSpotApi[IO](client, "tok", "https://api.hubapi.com").get("deals", None).map { out =>
      val r = out.hcursor.downField("results").downN(0)
      expect(r.get[String]("deal_id").toOption.contains("4111256677")) and
        expect(r.get[String]("pipeline").toOption.contains("UK Installers")) and
        expect(r.get[String]("segment").toOption.contains("installer")) and
        expect(r.get[String]("company_id").toOption.contains("5315090904")) and
        expect(r.get[String]("created").toOption.contains("2021-02-07")) and
        expect(r.get[Boolean]("is_closed").toOption.contains(false))
    }
  }

  private val lineItemSearch = parseJson("""
    {"results":[{"id":"1215308550","properties":{"name":"Hypervolt Home 3 Pro","quantity":"2","price":"435.18",
      "amount":"870.36","hs_sku":"HV-PR-1172","hs_lastmodifieddate":"1717238400000"}}]}""").toOption.get
  private val liAssoc = parseJson("""
    {"results":[{"from":{"id":"1215308550"},"to":[{"toObjectId":4111256677,"associationTypes":[{"label":"Primary"}]}]}]}""").toOption.get

  test("line_items: search + line_item→deal association → deal_line shape (sku, qty, price, amount, deal_id)") {
    val client = routingClient(p => if (p.contains("/associations/")) liAssoc else lineItemSearch)
    new HttpHubSpotApi[IO](client, "tok", "https://api.hubapi.com").get("line_items", None).map { out =>
      val r = out.hcursor.downField("results").downN(0)
      expect(r.get[String]("line_item_id").toOption.contains("1215308550")) and
        expect(r.get[String]("deal_id").toOption.contains("4111256677")) and
        expect(r.get[String]("sku").toOption.contains("HV-PR-1172")) and
        expect(r.get[String]("qty").toOption.contains("2")) and
        expect(r.get[String]("amount").toOption.contains("870.36"))
    }
  }

  test("an unwired object (tickets) is rejected — wired set is companies, contacts, deals, line_items") {
    new HttpHubSpotApi[IO](fakeClient(Json.obj()), "tok", "https://api.hubapi.com").get("tickets", None).attempt.map(e => expect(e.isLeft))
  }

  private val companyList = parseJson("""{"results":[{"id":"100"},{"id":"200"}]}""").toOption.get
  private val companyAssoc = parseJson("""
    {"results":[
      {"from":{"id":"100"},"to":[
        {"toObjectId":201,"associationTypes":[{"label":"Child Company"}]},
        {"toObjectId":202,"associationTypes":[{"label":"Child Company"}]}]},
      {"from":{"id":"200"},"to":[{"toObjectId":100,"associationTypes":[{"label":"Parent Company"}]}]}
    ]}""").toOption.get

  test("companyParentPairs: both directions → (child, parent) — CEF-style branch links") {
    val client = routingClient(p => if (p.contains("/associations/")) companyAssoc else companyList)
    new HttpHubSpotApi[IO](client, "tok", "https://api.hubapi.com").companyParentPairs().map { pairs =>
      // 100 has children 201,202 (Child Company); 200's parent is 100 (Parent Company)
      expect(pairs.toSet == Set(("201", "100"), ("202", "100"), ("200", "100")))
    }
  }
}
