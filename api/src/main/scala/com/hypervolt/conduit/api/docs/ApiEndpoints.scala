package com.hypervolt.conduit.api.docs

import cats.effect.Async
import com.hypervolt.conduit.api.auth.AuthService
import com.hypervolt.conduit.api.routes._
import com.hypervolt.conduit.document.DocumentStorage
import com.hypervolt.conduit.privacy.CryptoShred
import doobie.util.transactor.Transactor
import sttp.tapir.AnyEndpoint

// The canonical /api/v1 endpoint set, aggregated from every route module's `serverEndpoints` (spec 38 §5b).
// Each module's deps are dereferenced only at REQUEST time (Secured.base defers auth into a closure), so the
// endpoint DEFINITIONS build without a live DB/auth/storage — which lets both the live /openapi.yaml route and
// the no-boot drift-gate test (ApiDocsSpec) derive the same spec from one source of truth. ActivationStreamRoutes
// (raw SSE, no Tapir endpoints) and HealthRoutes (:9990 admin) are intentionally not part of the API surface.
object ApiEndpoints {
  def all[F[_]: Async](xa: Transactor[F], auth: AuthService[F]): List[AnyEndpoint] = {
    val storage = null.asInstanceOf[DocumentStorage[F]] // request-time only; never touched at endpoint definition
    val crypto  = null.asInstanceOf[CryptoShred]
    new AccessRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new ActivationRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new AttachmentRoutes[F](xa, auth, storage).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new AuditRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new CommerceRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new CommissionRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new CreditRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new CrmRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new DealDeskRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new DispatchRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new DocumentRoutes[F](xa, auth, storage).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new EntityStructureRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new ForecastRunRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new FreeShipmentRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new H6QRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new InboxRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new IntercompanyRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new InventoryRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new InvoiceVoidRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new OrderCommitmentRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new OrderLifecycleRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new PricingRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new PrivacyRoutes[F](xa, auth, crypto).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new ProcurementRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new ProofRoutes[F](xa, auth, tamperEnabled = false).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new PurchasingRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new ReturnRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new ShadowValidationRoutes[F](xa, auth, shadowMode = false).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new ShelfDetailRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new StripeWebhookRoutes[F](xa, None).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new TaxRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new TreasuryRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new UnitLifecycleRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      new WarrantyRoutes[F](xa, auth).serverEndpoints.map(_.endpoint: AnyEndpoint) :::
      Nil
  }
}
