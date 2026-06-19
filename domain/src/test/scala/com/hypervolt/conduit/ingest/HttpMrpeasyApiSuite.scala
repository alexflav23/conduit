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

// S2.2 (spec 37 §2): the live MRPeasy API normalizes the real REST shape into the canonical record the shared
// mrpOrder/mrpShipment handlers consume — crucially extracting the SERIAL NUMBERS a shipment line logs
// (products[].serials[].serial) into lines[].serials, which the handler maps to serial_unit (genealogy).
// Fixtures are the actual live shapes captured from /customer-orders and /shipments.
object HttpMrpeasyApiSuite extends SimpleIOSuite {

  private def fakeClient(body: Json): Client[IO] =
    Client[IO](_ => Resource.pure[IO, Response[IO]](Response[IO](Status.Ok).withEntity(body)))

  private def api(body: Json) = new HttpMrpeasyApi[IO](fakeClient(body), "ak", "pk", "https://app.mrpeasy.com/rest/v1")

  private val ordersV1 = parseJson("""
    [{"cust_ord_id":19,"code":"HYPV-CO-RETURN-0001","customer_name":"Morzak EV","created":"1620673589",
      "delivery_date":"1620773999","actual_delivery_date":"1621599035","status_txt":"Delivered",
      "total_price":409,"total_price_cur":409,
      "products":[{"item_code":"HV-PR-1172","quantity":2,"item_price":205,"total_price":410}]}]""").toOption.get

  private val shipmentsV1 = parseJson("""
    [{"shipment_id":56156,"code":"SHIP-056124","customer_order_code":"HYPV-CO-230137","rma_order_id":null,
      "created":"1620649831","delivery_date":"1620687600","status_txt":"Shipped",
      "products":[{"item_code":"HV-PR-1172","quantity_picked":2,
        "serials":[{"serial_id":134515,"serial":"03010080b45e5f84"},{"serial_id":134514,"serial":"030100e20c92d95a"}]}]}]""").toOption.get

  test("customer-orders: cust_ord_id→id, products→lines, status_txt→status, modified = latest ts") {
    api(ordersV1).get("customer-orders", None).map { out =>
      val r = out.hcursor.downN(0)
      expect(r.get[String]("id").toOption.contains("19")) and
        expect(r.get[String]("status").toOption.contains("Delivered")) and
        expect(r.get[String]("modified").toOption.contains("1621599035")) and // max(created, delivery, actual)
        expect(r.downField("lines").downN(0).get[String]("item_code").toOption.contains("HV-PR-1172")) and
        expect(r.downField("lines").downN(0).get[Int]("qty").toOption.contains(2))
    }
  }

  test("shipments: serials extracted to lines[].serials (the genealogy spine)") {
    api(shipmentsV1).get("shipments", None).map { out =>
      val line = out.hcursor.downN(0).downField("lines").downN(0)
      expect(out.hcursor.downN(0).get[String]("id").toOption.contains("56156")) and
        expect(out.hcursor.downN(0).get[String]("order_code").toOption.contains("HYPV-CO-230137")) and
        expect(line.get[List[String]]("serials").toOption.contains(List("03010080b45e5f84", "030100e20c92d95a")))
    }
  }

  test("the connector consumes the normalized shipment: keys on id, advances watermark, serials survive") {
    new MrpeasyConnector[IO](api(shipmentsV1)).pullSince("shipments", None).map { batch =>
      val payload = batch.records.head.payload
      expect(batch.records.map(_.sourceId) == List("56156")) and
        expect(batch.nextCursor.contains(SyncCursor("1620687600"))) and
        expect(
          payload.hcursor.downField("lines").downN(0).get[List[String]]("serials").toOption
            .contains(List("03010080b45e5f84", "030100e20c92d95a"))
        )
    }
  }

  test("a warm cursor returns only newer rows; an unwired endpoint is rejected") {
    val warm = api(ordersV1).get("customer-orders", Some("1621599035")).map(out => expect(out.asArray.exists(_.isEmpty)))
    val bad  = api(Json.arr()).get("stock-lots", None).attempt.map(e => expect(e.isLeft))
    warm.flatMap(a => bad.map(b => a and b))
  }
}
