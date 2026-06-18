package com.hypervolt.conduit.api.docs

import io.circe.syntax._
import sttp.apispec.openapi.circe._
import sttp.tapir.AnyEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

// The formal API documentation language (spec 38 §5b): Tapir endpoints ARE an OpenAPI 3.1 description. This turns
// the aggregated endpoint set into the canonical OpenAPI JSON — served live at /api/v1/openapi.json (rendered by
// Scalar at /api/v1/docs), committed to api/openapi.json, fed to openapi-typescript, and diffed in CI for drift.
// One source of truth: the endpoints (ApiEndpoints.all). Endpoints sorted for a stable, diff-friendly document.
object ApiDocs {
  val title   = "Conduit API"
  val version = "v1"

  def openApiJson(endpoints: List[AnyEndpoint]): String =
    OpenAPIDocsInterpreter()
      .toOpenAPI(
        endpoints.sortBy(e => e.showPathTemplate() + " " + e.method.map(_.method).getOrElse("")),
        title,
        version
      )
      .asJson
      .deepDropNullValues
      .spaces2
}
