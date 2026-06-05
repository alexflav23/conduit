package com.hypervolt.conduit.intercompany

import java.util.UUID

// The procurement-chain topology (doc 13 §1) is pure data on `entity`: walk `procurement_parent_id` from an
// operating market up to the external root (the node that buys from a real supplier). Year-1 = zero/one hop;
// the multi-tier Singapore chain is more hops — same code, no branch. This resolver is the only place topology
// lives; everything downstream consumes the hops it yields.

final case class EntityNode(
    id: UUID,
    name: String,
    functionalCurrency: String,
    jurisdiction: String,
    procurementParentId: Option[UUID]
)

final case class Hop(from: EntityNode, to: EntityNode, hopSeq: Int) {
  def fromCurrency: String   = from.functionalCurrency
  def toCurrency: String     = to.functionalCurrency
  def isCrossBorder: Boolean = from.jurisdiction != to.jurisdiction
}

final case class Chain(externalRoot: EntityNode, hops: List[Hop])

object Topology {

  // Returns the hops from the operating entity up to the external root, hop_seq 1 = nearest the root.
  def procurementChain(operating: UUID, byId: Map[UUID, EntityNode]): Either[String, Chain] =
    byId.get(operating) match {
      case None => Left(s"unknown entity $operating")
      case Some(start) =>
        walk(start, byId, List.empty, Set(start.id)).map {
          case (root, hopsRootFirst) =>
            Chain(root, hopsRootFirst.zipWithIndex.map { case (h, i) => h.copy(hopSeq = i + 1) })
        }
    }

  @annotation.tailrec
  private def walk(
      cur: EntityNode,
      byId: Map[UUID, EntityNode],
      acc: List[Hop],
      seen: Set[UUID]
  ): Either[String, (EntityNode, List[Hop])] =
    cur.procurementParentId match {
      case None                            => Right((cur, acc))
      case Some(pid) if seen.contains(pid) => Left(s"procurement chain cycle at $pid")
      case Some(pid) =>
        byId.get(pid) match {
          case None         => Left(s"procurement parent $pid not found")
          case Some(parent) =>
            // parent sells to cur; prepend so the root-nearest hop ends up first
            walk(parent, byId, Hop(parent, cur, 0) :: acc, seen + pid)
        }
    }
}
