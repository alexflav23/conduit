package com.hypervolt.conduit.purchasing

import cats.effect.Async
import cats.syntax.all._
import com.hypervolt.conduit.money.Currency
import com.hypervolt.conduit.money.Money
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.util.transactor.Transactor
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Inbound tranches as first-class citizens (M9c, user spec): a tranche is one delivery batch against OUR PO
// to a contract manufacturer — Volex Poland arrives by truck, Luxshare Suzhou by rail or sea — carrying ITS
// OWN inbound freight. On receipt: the freight is spread over the tranche's units with the CONSERVING
// allocate (Σ parts == total, doc 14), lands in landed_cost_component per line (so per-unit landed cost
// differs by lane, as it must), the GRN links back to the tranche, and every SKU line snapshots its
// roll-forward balance — an immutable as-of statement.
final case class TrancheLine(variantId: UUID, qty: Int, poLineId: UUID, unitCostUsd: BigDecimal, fxRate: BigDecimal)

final class TrancheService[F[_]: Async](xa: Transactor[F], purchasing: PurchasingService[F]) {

  def plan(
      poId: UUID,
      transportMode: String,
      originSite: String,
      freight: BigDecimal,
      currency: String,
      lines: List[TrancheLine],
      expectedShip: Option[LocalDate] = None,
      expectedArrival: Option[LocalDate] = None,
      carrierRef: Option[String] = None
  ): F[UUID] = {
    val tx = for {
      seq <- sql"SELECT COALESCE(MAX(seq), 0) + 1 FROM purchase_tranche WHERE po_id = $poId".query[Int].unique
      id  <- sql"""INSERT INTO purchase_tranche
                    (po_id, seq, transport_mode, origin_site, freight_amount, freight_currency,
                     expected_ship_date, expected_arrival_date, carrier_ref)
                  VALUES ($poId, $seq, $transportMode, $originSite, $freight, $currency,
                          $expectedShip, $expectedArrival, $carrierRef)
                  RETURNING id""".query[UUID].unique
      _   <- lines.traverse_(l => sql"""INSERT INTO purchase_tranche_line (tranche_id, product_variant_id, qty)
              VALUES ($id, ${l.variantId}, ${l.qty})""".update.run)
    } yield id
    tx.transact(xa)
  }

  def markShipped(trancheId: UUID, at: Instant): F[Int] =
    sql"""UPDATE purchase_tranche SET status = 'in_transit', shipped_at = $at
          WHERE id = $trancheId AND status = 'planned'""".update.run.transact(xa)

  // Receive the whole tranche: per-line GRN through the existing receiving machinery (lot batch, stock,
  // serials, backorder auto-fill), the tranche freight conservingly allocated over units, GRN + landed-cost
  // rows stamped with the tranche, and the roll-forward balance snapshotted per SKU.
  def receive(
      trancheId: UUID,
      entity: UUID,
      locationId: UUID,
      lines: List[TrancheLine],
      serialsByVariant: Map[UUID, List[String]] = Map.empty,
      receivedDate: LocalDate
  ): F[Int] =
    header(trancheId).flatMap {
      case None                                            => 0.pure[F]
      case Some((_, _, status, _)) if status == "received" => 0.pure[F] // idempotent re-receive
      case Some((poId, freight, _, currency)) =>
        val cur = Currency.fromCode(currency).getOrElse(Currency.GBP)
        val freightShares: List[BigDecimal] =
          if (lines.isEmpty) Nil
          else
            Money
              .allocate(Money(freight, cur), lines.map(l => BigDecimal(l.qty)).toVector)
              .map(_.amount)
              .toList
        lines
          .zip(freightShares)
          .traverse {
            case (l, freightShare) =>
              purchasing
                .receive(
                  poId,
                  entity,
                  locationId,
                  ReceiveLine(
                    l.poLineId,
                    l.variantId,
                    l.qty,
                    l.unitCostUsd,
                    l.fxRate,
                    freightShare,
                    BigDecimal(0),
                    serialsByVariant.getOrElse(l.variantId, Nil),
                    currency
                  ),
                  receivedDate
                )
                .flatMap(_ => stampAndSnapshot(trancheId, poId, l.variantId).transact(xa))
          }
          .flatMap(_ => sql"""UPDATE purchase_tranche SET status = 'received', received_at = now()
                  WHERE id = $trancheId""".update.run.transact(xa))
    }

  private def header(trancheId: UUID): F[Option[(UUID, BigDecimal, String, String)]] =
    sql"""SELECT po_id, freight_amount, status, freight_currency
          FROM purchase_tranche WHERE id = $trancheId"""
      .query[(UUID, BigDecimal, String, String)]
      .option
      .transact(xa)

  // The GRN and landed-cost rows just written are the newest for this PO+variant — stamp them with the
  // tranche, then snapshot the roll-forward balance (the live on-hand after this receipt).
  private def stampAndSnapshot(trancheId: UUID, poId: UUID, variant: UUID): ConnectionIO[Int] =
    for {
      _       <- sql"""UPDATE goods_receipt SET tranche_id = $trancheId
                 WHERE id = (SELECT g.id FROM goods_receipt g
                             JOIN goods_receipt_line gl ON gl.grn_id = g.id
                             JOIN po_line pl ON pl.id = gl.po_line_id
                             WHERE g.po_id = $poId AND pl.product_variant_id = $variant
                               AND g.tranche_id IS NULL
                             ORDER BY g.id DESC LIMIT 1)""".update.run
      _       <- sql"""UPDATE landed_cost_component SET tranche_id = $trancheId
                 WHERE po_id = $poId AND tranche_id IS NULL""".update.run
      balance <- sql"""SELECT COALESCE(SUM(qty_on_hand), 0) FROM stock_item
                       WHERE product_variant_id = $variant""".query[BigDecimal].unique
      n       <- sql"""UPDATE purchase_tranche_line SET balance_after = $balance
                 WHERE tranche_id = $trancheId AND product_variant_id = $variant""".update.run
    } yield n
}
