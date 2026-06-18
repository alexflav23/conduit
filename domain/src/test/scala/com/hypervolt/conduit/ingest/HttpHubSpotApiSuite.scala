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

  test("an unwired object (deals) is rejected — S2.1 ships companies + contacts") {
    new HttpHubSpotApi[IO](fakeClient(Json.obj()), "tok", "https://api.hubapi.com").get("deals", None).attempt.map(e => expect(e.isLeft))
  }
}
