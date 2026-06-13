package com.hypervolt.conduit.ingest

import cats.effect.IO
import io.circe.Json
import io.circe.parser.{parse => parseJson}
import weaver.SimpleIOSuite

// M-Ingest slice 3 (spec doc 33 §4): the HubSpot connector's parse + cursor, fixture-driven. A fake HubSpotApi
// returns canned CRM-v3 bodies; the connector must key records on the top-level id and advance the cursor to the
// page's max hs_lastmodifieddate (epoch-ms, nested under properties).
object HubSpotConnectorSuite extends SimpleIOSuite {

  private def fakeApi(byType: Map[String, Json]): HubSpotApi[IO] =
    (objectType: String, _: Option[String]) => IO.pure(byType.getOrElse(objectType, Json.obj()))

  private val dealsBody = parseJson("""
    {"results":[
      {"id":"d-1","properties":{"dealname":"HV Wholesale Q3","amount":"24000","dealstage":"closedwon","hs_lastmodifieddate":"1717238400000"}},
      {"id":"d-2","properties":{"dealname":"HV Energy Co","amount":"9000","dealstage":"qualifiedtobuy","hs_lastmodifieddate":"1717497600000"}}
    ],"paging":{"next":{"after":"2"}}}""").toOption.get

  test("pullSince(deals) keys on the top-level id and advances the cursor to the max hs_lastmodifieddate") {
    val conn = new HubSpotConnector[IO](fakeApi(Map("deals" -> dealsBody)))
    conn.pullSince("deals", None).map { batch =>
      expect(batch.records.map(_.sourceId) == List("d-1", "d-2")) and
        expect(batch.records.forall(_.dataset == "deals")) and
        expect(
          batch.records.head.payload.hcursor
            .downField("properties")
            .get[String]("dealname")
            .toOption
            .contains("HV Wholesale Q3")
        ) and
        expect(batch.nextCursor.contains(SyncCursor("1717497600000"))) and // numeric max of the two epoch-ms
        expect(batch.complete)
    }
  }

  test("an empty page yields no records and no cursor advance") {
    val conn = new HubSpotConnector[IO](fakeApi(Map.empty))
    conn.pullSince("contacts", Some(SyncCursor("1717000000000"))).map { batch =>
      expect(batch.records.isEmpty) and expect(batch.nextCursor.isEmpty)
    }
  }

  test("datasets cover the four CRM objects; an unknown object is rejected") {
    val conn = new HubSpotConnector[IO](fakeApi(Map.empty))
    conn.pullSince("widgets", None).attempt.map { e =>
      expect(conn.source == "hubspot") and
        expect(conn.datasets == List("companies", "contacts", "deals", "line_items")) and
        expect(e.isLeft)
    }
  }
}
