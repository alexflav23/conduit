package com.hypervolt.conduit.ingest

import cats.effect.IO
import io.circe.Json
import io.circe.parser.{parse => parseJson}
import weaver.SimpleIOSuite

// M-Ingest slice 4 (spec doc 33 §4): the MRPeasy connector's parse + cursor. MRPeasy returns a top-level JSON
// array with numeric ids + a `modified` unix watermark (number or string); the connector keys records and
// advances the cursor to the page's max modified.
object MrpeasyConnectorSuite extends SimpleIOSuite {

  private def fakeApi(byEndpoint: Map[String, Json]): MrpeasyApi[IO] =
    (endpoint: String, _: Option[String]) => IO.pure(byEndpoint.getOrElse(endpoint, Json.arr()))

  // numeric id + numeric `modified` (the realistic MRPeasy shape), as a bare top-level array.
  private val ordersBody = parseJson("""
    [ {"id":5001,"reference":"CO-5001","customer":"Acme","total":1234.50,"modified":1717238400},
      {"id":5002,"reference":"CO-5002","customer":"Globex","total":99.00,"modified":1717497600} ]""").toOption.get

  test("pullSince(customer_orders) keys numeric ids and advances the cursor to the max modified") {
    val conn = new MrpeasyConnector[IO](fakeApi(Map("customer-orders" -> ordersBody)))
    conn.pullSince("customer_orders", None).map { batch =>
      expect(batch.records.map(_.sourceId) == List("5001", "5002")) and
        expect(batch.records.forall(_.dataset == "customer_orders")) and
        expect(batch.records.head.payload.hcursor.get[String]("reference").toOption.contains("CO-5001")) and
        expect(batch.nextCursor.contains(SyncCursor("1717497600"))) and
        expect(batch.complete)
    }
  }

  test("an {items:[...]} wrapper and a string watermark are both accepted") {
    val wrapped = parseJson("""{"items":[{"id":"L-9","modified":"1717000000"}]}""").toOption.get
    val conn    = new MrpeasyConnector[IO](fakeApi(Map("stock-lots" -> wrapped)))
    conn.pullSince("stock_lots", None).map { batch =>
      expect(batch.records.map(_.sourceId) == List("L-9")) and expect(
        batch.nextCursor.contains(SyncCursor("1717000000"))
      )
    }
  }

  test("an empty result yields no records/cursor; an unknown dataset is rejected") {
    val conn = new MrpeasyConnector[IO](fakeApi(Map.empty))
    for {
      empty <- conn.pullSince("shipments", Some(SyncCursor("1")))
      bad   <- conn.pullSince("widgets", None).attempt
    } yield expect(empty.records.isEmpty && empty.nextCursor.isEmpty) and
      expect(conn.datasets == List("articles", "customer_orders", "purchase_orders", "shipments", "stock_lots")) and
      expect(bad.isLeft)
  }
}
