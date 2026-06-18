package com.hypervolt.conduit.api.docs

import cats.effect.IO

// The committed-spec generator (spec 38 §5b). `sbt "api/runMain com.hypervolt.conduit.api.docs.GenerateOpenApi"`
// regenerates api/openapi.json from the live Tapir endpoint set; CI runs it and `git diff --exit-code` fails on
// drift between the code and the published spec. Deps are null because endpoint DEFINITIONS never dereference them
// (auth is deferred into the request-time closure — see ApiEndpoints). openapi-typescript reads the same file.
object GenerateOpenApi {
  def main(args: Array[String]): Unit = {
    val json = ApiDocs.openApiJson(ApiEndpoints.all[IO](null, null))
    val out  = if (args.nonEmpty) args(0) else "api/openapi.json"
    java.nio.file.Files.write(java.nio.file.Paths.get(out), json.getBytes("UTF-8"))
    println(s"wrote $out (${json.length} bytes, ${json.split("\n").length} lines)")
  }
}
