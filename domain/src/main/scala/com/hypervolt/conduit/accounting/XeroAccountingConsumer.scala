package com.hypervolt.conduit.accounting

import cats.effect.Async
import cats.effect.std.AtomicCell
import cats.syntax.all._
import com.hypervolt.conduit.config.XeroConfig
import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto._
import io.circe.syntax._
import java.time.Instant
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.headers._
import org.typelevel.log4cats.slf4j.Slf4jLogger

// Xero implementation of the AccountingConsumer boundary — ported from Athena's XeroService and adapted to
// Conduit's ERP-agnostic InvoiceRequest. OAuth2 client-credentials (token cached + refreshed in an AtomicCell),
// PUT /Invoices (ACCREC). Idempotency on re-delivery is handled upstream by the InvoiceDispatcher's dedupe on
// event_id, and the Conduit invoice_no rides as both InvoiceNumber and Reference for traceability in Xero.
object XeroAccountingConsumer {

  private final case class TokenState(accessToken: String, expiresAt: Instant)
  private final case class TokenResponse(access_token: String, expires_in: Long)
  private implicit val tokenDecoder: Decoder[TokenResponse] = deriveDecoder

  private final case class XLineItem(
      Description: String,
      Quantity: Int,
      UnitAmount: BigDecimal,
      ItemCode: String,
      TaxType: Option[String]
  )
  private final case class XContact(Name: String)
  private final case class XInvoice(
      `Type`: String,
      InvoiceNumber: String,
      Reference: String,
      Status: String,
      CurrencyCode: String,
      DueDate: String,
      Contact: XContact,
      LineItems: List[XLineItem]
  )
  private implicit val liEnc: Encoder[XLineItem] = deriveEncoder[XLineItem].mapJson(_.deepDropNullValues)
  private implicit val coEnc: Encoder[XContact]  = deriveEncoder
  private implicit val invEnc: Encoder[XInvoice] = deriveEncoder[XInvoice].mapJson(_.deepDropNullValues)

  private final case class XInvoiceResult(InvoiceID: String)
  private final case class XInvoicesResponse(Invoices: List[XInvoiceResult])
  private implicit val resDec: Decoder[XInvoiceResult]     = deriveDecoder
  private implicit val ressDec: Decoder[XInvoicesResponse] = deriveDecoder

  // Status-only update to void an invoice (Xero: POST /Invoices with the InvoiceID + Status VOIDED).
  private final case class XVoid(InvoiceID: String, Status: String)
  private implicit val voidEnc: Encoder[XVoid] = deriveEncoder

  // Build the consumer. If Xero isn't configured (no client id), wire a local no-op so dev/CI never call out.
  def build[F[_]: Async](client: Client[F], cfg: XeroConfig): F[AccountingConsumer[F]] = {
    val logger = Slf4jLogger.getLogger[F]
    if (!cfg.enabled)
      logger.warn("Xero client id missing/placeholder — wiring no-op AccountingConsumer (local/dev only)").as {
        new AccountingConsumer[F] {
          def createInvoice(req: InvoiceRequest): F[Either[String, String]] =
            logger.info(s"[xero no-op] would create invoice ${req.invoiceNo}").as(Right(s"NOOP-${req.invoiceNo}"))
          def voidInvoice(externalRef: String, invoiceNo: String, reason: String): F[Either[String, Unit]] =
            logger.info(s"[xero no-op] would void invoice $invoiceNo ($reason)").as(Right(()))
        }
      }
    else
      AtomicCell[F].of(TokenState("", Instant.EPOCH)).map(cell => new Real[F](client, cfg, cell))
  }

  private final class Real[F[_]: Async](client: Client[F], cfg: XeroConfig, tokenCell: AtomicCell[F, TokenState])
      extends AccountingConsumer[F] {

    private val logger = Slf4jLogger.getLogger[F]
    private val apiUri = Uri.unsafeFromString(cfg.apiUrl)

    private def freshToken: F[String] =
      tokenCell.evalModify { st =>
        if (Instant.now().isBefore(st.expiresAt.minusSeconds(30))) Async[F].pure((st, st.accessToken))
        else acquireToken.map(t => (t, t.accessToken))
      }

    private def acquireToken: F[TokenState] =
      client
        .expect[TokenResponse](
          Request[F](
            method = Method.POST,
            uri = Uri.unsafeFromString(cfg.identityUrl) / "connect" / "token",
            headers = Headers(Authorization(BasicCredentials(cfg.clientId, cfg.clientSecret)))
          ).withEntity(UrlForm("grant_type" -> "client_credentials", "scope" -> cfg.scope))
        )(jsonOf[F, TokenResponse])
        .map(r => TokenState(r.access_token, Instant.now().plusSeconds(r.expires_in)))
        .onError(t => logger.error(t)("Failed to acquire Xero auth token"))

    def createInvoice(req: InvoiceRequest): F[Either[String, String]] =
      freshToken.flatMap { token =>
        val body = XInvoice(
          `Type` = "ACCREC",
          InvoiceNumber = req.invoiceNo,
          Reference = req.reference,
          Status = "AUTHORISED",
          CurrencyCode = req.currency,
          DueDate = req.dueDate.toString,
          Contact = XContact(req.contactName),
          LineItems = req.lines.map(l => XLineItem(l.description, l.qty, l.unitAmountExVat, l.sku, l.taxType))
        )
        val baseReq = Request[F](method = Method.PUT, uri = apiUri / "Invoices")
          .withEntity(body.asJson)
          .putHeaders(
            Authorization(Credentials.Token(AuthScheme.Bearer, token)),
            Accept(MediaType.application.json)
          )
        val request = cfg.tenantId.fold(baseReq)(t =>
          baseReq.putHeaders(Header.Raw(org.typelevel.ci.CIString("Xero-tenant-id"), t))
        )
        client
          .expect[XInvoicesResponse](request)(jsonOf[F, XInvoicesResponse])
          .map(_.Invoices.headOption.map(_.InvoiceID).toRight(s"Xero returned no invoice for ${req.invoiceNo}"))
          .handleError(t => Left(s"Xero invoice push failed for ${req.invoiceNo}: ${t.getMessage}"))
      }

    def voidInvoice(externalRef: String, invoiceNo: String, reason: String): F[Either[String, Unit]] =
      freshToken.flatMap { token =>
        val body = XVoid(InvoiceID = externalRef, Status = "VOIDED")
        val baseReq = Request[F](method = Method.POST, uri = apiUri / "Invoices")
          .withEntity(body.asJson)
          .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)), Accept(MediaType.application.json))
        val request = cfg.tenantId.fold(baseReq)(t =>
          baseReq.putHeaders(Header.Raw(org.typelevel.ci.CIString("Xero-tenant-id"), t))
        )
        client
          .expect[XInvoicesResponse](request)(jsonOf[F, XInvoicesResponse])
          .as(Right(()): Either[String, Unit])
          .handleError(t => Left(s"Xero void failed for $invoiceNo: ${t.getMessage}"))
      }
  }
}
