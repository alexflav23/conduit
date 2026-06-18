package com.hypervolt.conduit.api.routes

import cats.effect.Async
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.docs.ApiDocs
import com.hypervolt.conduit.api.docs.ApiEndpoints
import doobie.util.transactor.Transactor
import org.http4s.HttpRoutes
import org.http4s.MediaType
import org.http4s.headers.`Content-Type`
import org.http4s.dsl.Http4sDsl

// Serves the live OpenAPI spec (generated from the Tapir endpoints — the formal API reference) and a Scalar
// rendering of it (spec 38 §5b). Unauthenticated by design: the spec is the API's published shape, and Scalar is
// the human reference the desk's "API reference" links to. The spec is computed once at construction (endpoints
// are static). Raw http4s (not Tapir) so the docs surface doesn't recurse into its own spec.
final class OpenApiRoutes[F[_]: Async](xa: Transactor[F], auth: AuthService[F]) {
  private val dsl = new Http4sDsl[F] {}
  import dsl._

  private val spec: String = ApiDocs.openApiJson(ApiEndpoints.all[F](xa, auth))

  private val scalar: String =
    """<!doctype html><html><head><title>Conduit API reference</title><meta charset="utf-8">""" +
      """<meta name="viewport" content="width=device-width, initial-scale=1"></head>""" +
      """<body><script id="api-reference" data-url="/api/v1/openapi.json"></script>""" +
      """<script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script></body></html>"""

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "api" / "v1" / "openapi.json" =>
      Ok(spec, `Content-Type`(MediaType.application.json))
    case GET -> Root / "api" / "v1" / "docs" =>
      Ok(scalar, `Content-Type`(MediaType.text.html))
  }
}
