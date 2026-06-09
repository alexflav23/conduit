package com.hypervolt.conduit.pricing

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.ledger.TigerBeetleLedger
import com.hypervolt.conduit.money.Currency
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.Instant
import java.util.UUID

// The CONTINUOUS accrual trigger (doc 24 §5.2): whenever an order's economics change (placed / amended / cancelled /
// voided), re-run the expected-rebate true-up for every active retrospective agreement covering the buyer. Because
// accrueExpected is a state-keyed true-up to a reproducible projection, the trigger only needs to be SUFFICIENT,
// never precise — re-running converges on the correct liability and a same-state re-run is a TB no-op. Entity-less
// orders (simulation/migration fixtures) never reach the ledger.
final class RebateAccrualService[F[_]: Async](xa: Transactor[F], ledger: TigerBeetleLedger[F]) {

  private val rebates = new RebateService[F](xa, ledger)

  def accrueForOrder(orderId: UUID, asOf: Instant): F[Int] =
    orderContext(orderId).transact(xa).flatMap {
      case Some((party, Some(entity))) =>
        retrospectiveAgreements(party)
          .transact(xa)
          .flatMap(
            _.traverse(
              {
                case (agreementId, ccy) =>
                  Currency.fromCode(ccy).traverse_(c => rebates.accrueExpected(agreementId, entity, c, asOf))
              }
            ).map(_.size)
          )
      case _ => 0.pure[F]
    }

  private def orderContext(orderId: UUID): ConnectionIO[Option[(UUID, Option[UUID])]] =
    sql"""SELECT sold_to_party_id, entity_id FROM "order" WHERE id = $orderId"""
      .query[(UUID, Option[UUID])]
      .option

  // The buyer's active retrospective agreements — named directly (customer_set) or via segment/sector scope,
  // mirroring the resolution scopes (doc 24 §2).
  private def retrospectiveAgreements(party: UUID): ConnectionIO[List[(UUID, String)]] =
    sql"""SELECT pa.id, pa.currency FROM price_agreement pa
          WHERE pa.base_volume_basis = 'cumulative_retrospective' AND pa.status = 'active'
            AND (EXISTS (SELECT 1 FROM price_agreement_customer pac
                         WHERE pac.agreement_id = pa.id AND pac.party_id = $party)
                 OR (pa.applies_to = 'segment'
                     AND EXISTS (SELECT 1 FROM party p WHERE p.id = $party AND p.segment = pa.scope_value))
                 OR (pa.applies_to = 'sector'
                     AND EXISTS (SELECT 1 FROM party p WHERE p.id = $party AND p.sector = pa.scope_value)))"""
      .query[(UUID, String)]
      .to[List]
}
