package com.hypervolt.conduit.crm

import cats.effect.Async
import cats.syntax.all._
import doobie.implicits._
import doobie.util.transactor.Transactor

// Determines wholesale BRANCHES from HubSpot's authoritative parent/child company links (S2). The careful part:
// HubSpot only sets these for a few accounts (CEF (Primary) → ~392 branches), so this COMPLEMENTS — never
// replaces — the name-heuristic branch detection. Each (child_company, parent_company) pair is resolved to its
// MASTER party via account_source_link (so a HubSpot company merged into an MRPeasy master resolves correctly),
// then the child's parent_party_id is set ONLY when:
//   • the child has no parent yet (never override a manual/heuristic parent),
//   • child and parent resolve to different orgs (no self-parenting),
//   • it would not create an immediate 2-cycle (parent isn't already a child of this child).
// Idempotent: a re-run only fills still-null parents. The relations are persisted (hubspot_company_relation) for audit.
final class BranchLinkService[F[_]: Async](xa: Transactor[F]) {

  // Persist the authoritative pairs (child has exactly one parent → upsert on child).
  def store(pairs: List[(String, String)]): F[Int] =
    pairs
      .filter { case (c, p) => c.nonEmpty && p.nonEmpty && c != p }
      .traverse {
        case (child, parent) =>
          sql"""INSERT INTO hubspot_company_relation (child_company_id, parent_company_id)
                VALUES ($child, $parent)
                ON CONFLICT (child_company_id) DO UPDATE SET
                  parent_company_id = EXCLUDED.parent_company_id, last_seen = now()""".update.run
      }
      .map(_.sum)
      .transact(xa)

  // Apply the relations to the party hierarchy — set-based, safe, idempotent. Returns the number of branches linked.
  def apply(): F[Int] =
    sql"""WITH resolved AS (
            SELECT cl.party_id AS child, pl.party_id AS parent
            FROM hubspot_company_relation r
            JOIN account_source_link cl
              ON cl.source_system = 'hubspot_company' AND cl.source_id = r.child_company_id AND cl.status = 'linked'
            JOIN account_source_link pl
              ON pl.source_system = 'hubspot_company' AND pl.source_id = r.parent_company_id AND pl.status = 'linked'
            WHERE cl.party_id <> pl.party_id
          )
          UPDATE party SET parent_party_id = resolved.parent
          FROM resolved
          WHERE party.id = resolved.child
            AND party.parent_party_id IS NULL          -- never override an existing (manual/heuristic) parent
            AND party.is_organization
            AND party.status <> 'merged'
            AND NOT EXISTS (SELECT 1 FROM party gp WHERE gp.id = resolved.parent AND gp.parent_party_id = resolved.child)
       """.update.run.transact(xa)
}
