package com.hypervolt.conduit.pricing

import cats.effect.Async
import cats.syntax.all._
import doobie.ConnectionIO
import doobie.implicits._
import doobie.util.transactor.Transactor
import java.time.Instant
import java.util.UUID

// Orchestrates a quote: resolve each line's ADLP price from the DB, then assemble totals (pure).
final class QuoteService[F[_]: Async](xa: Transactor[F]) {

  def quote(
      channel: UUID,
      market: UUID,
      entity: Option[UUID],
      currency: String,
      lines: List[QuoteLine],
      customer: Option[UUID],
      asOf: Instant
  ): F[Either[String, QuoteResult]] = {
    val program: ConnectionIO[Either[String, QuoteResult]] =
      lines
        .traverse(line => resolveLine(channel, market, entity, currency, line, customer, asOf))
        .map { results =>
          results.collectFirst { case Left(err) => err } match {
            case Some(err) => Left(err)
            case None      => Right(PricingService.assemble(results.collect { case Right(r) => r }))
          }
        }
    program.transact(xa)
  }

  private def resolveLine(
      channel: UUID,
      market: UUID,
      entity: Option[UUID],
      currency: String,
      line: QuoteLine,
      customer: Option[UUID],
      asOf: Instant
  ): ConnectionIO[Either[String, QuoteLineResult]] =
    VariantRepo.lookupBySku(line.sku).flatMap {
      case None => (Left(s"unknown sku: ${line.sku}"): Either[String, QuoteLineResult]).pure[ConnectionIO]
      case Some((variantId, productClass)) =>
        TierResolver
          .candidates(variantId, productClass, channel, market, entity, currency, line.qty, customer, asOf)
          .map { candidates =>
            PricingService.resolve(candidates, channel, market, entity) match {
              case None      => Left(s"no active price for ${line.sku}")
              case Some(res) => Right(PricingService.priceLine(res, line))
            }
          }
    }
}
