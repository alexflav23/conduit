package com.hypervolt.conduit.crm

import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import io.circe.Json
import java.util.UUID

// Master-account review + manual merge (doc 02). The fuzzy candidates the model didn't auto-merge (<0.9 or no
// verdict) surface here with the model's suggested winner + reasoning; a human accepts (merge), rejects, or marks
// a wholesaler branch (parent_party_id). Manual merges carry merged_by=<user> so the recomputable ignition apply
// (which only reverses merged_by='ignition') never undoes them; the apply also skips companies a human decided.
object MasterAccountRepo {

  // Review queue: one row per HubSpot company with pending candidates, the model's suggestion (winner name +
  // confidence + reasoning) and the top candidate accounts to choose from. Best suggestions first.
  def reviewQueue(q: Option[String], limit: Int, offset: Int): ConnectionIO[List[Json]] =
    (fr"""SELECT jsonb_build_object(
            'hs_company_id', c.source_id, 'hs_name', max(c.source_name), 'hs_domain', max(hcr.domain),
            'model_suggestion', max(v.merge_into_name), 'model_confidence', max(v.confidence), 'model_reason', max(v.reason),
            'candidates', (jsonb_agg(DISTINCT jsonb_build_object(
                'party_id', c.party_id::text, 'name', regexp_replace(p.display_name,'^MRP:\s*',''),
                'orders', (SELECT count(*) FROM "order" o WHERE o.sold_to_party_id = c.party_id),
                'score', round(c.score,3)))))
          FROM account_link_candidate c
          JOIN party p ON p.id = c.party_id
          LEFT JOIN hubspot_company_raw hcr ON hcr.company_id = c.source_id
          LEFT JOIN hubspot_match_verdict v ON v.hs_company_id = c.source_id
          WHERE c.status = 'pending' """
      ++ q.filter(_.nonEmpty).map(t => fr"AND c.source_name ILIKE ${"%" + t + "%"}").getOrElse(fr"")
      ++ fr"""GROUP BY c.source_id
              ORDER BY max(v.confidence) DESC NULLS LAST, max(c.score) DESC
              LIMIT $limit OFFSET $offset""")
      .query[Json]
      .to[List]

  def reviewCount(q: Option[String]): ConnectionIO[Long] =
    (fr"""SELECT count(DISTINCT c.source_id) FROM account_link_candidate c WHERE c.status = 'pending' """
      ++ q.filter(_.nonEmpty).map(t => fr"AND c.source_name ILIKE ${"%" + t + "%"}").getOrElse(fr"")).query[Long].unique

  // Merge a HubSpot company's party (loser) into the chosen master (winner), with full lineage. Manual (by user).
  def merge(winner: UUID, hsCompanyId: String, by: String, reason: String): ConnectionIO[Int] =
    sql"""SELECT party_id FROM account_source_link WHERE source_system = 'hubspot_company' AND source_id = $hsCompanyId LIMIT 1"""
      .query[UUID]
      .option
      .flatMap {
        case Some(loser) if loser != winner =>
          sql"""UPDATE account_source_link SET party_id = $winner, merged_from_party_id = $loser WHERE party_id = $loser""".update.run *>
            sql"""UPDATE contact SET party_id = $winner, merged_from_party_id = $loser WHERE party_id = $loser""".update.run *>
            sql"""UPDATE party SET status = 'merged', merged_into_party_id = $winner WHERE id = $loser""".update.run *>
            sql"""INSERT INTO account_merge (loser_party_id, winner_party_id, method, confidence, reason, sources_moved, contacts_moved, merged_by)
                  VALUES ($loser, $winner, 'manual', 1.000, $reason,
                    (SELECT count(*) FROM account_source_link WHERE merged_from_party_id = $loser),
                    (SELECT count(*) FROM contact WHERE merged_from_party_id = $loser), $by)""".update.run *>
            sql"""UPDATE account_link_candidate SET status = 'accepted', reviewed_by = $by, reviewed_at = now()
                  WHERE source_id = $hsCompanyId AND party_id = $winner""".update.run *>
            sql"""UPDATE account_link_candidate SET status = 'rejected', reviewed_by = $by, reviewed_at = now()
                  WHERE source_id = $hsCompanyId AND party_id <> $winner AND status = 'pending'""".update.run
        case _ => 0.pure[ConnectionIO]
      }

  def reject(hsCompanyId: String, by: String): ConnectionIO[Int] =
    sql"""UPDATE account_link_candidate SET status = 'rejected', reviewed_by = $by, reviewed_at = now()
          WHERE source_id = $hsCompanyId AND status = 'pending'""".update.run

  // Mark a wholesaler branch (CEF Bristol → CEF): the child keeps its own identity/orders, rolls up to the parent.
  def setParent(child: UUID, parent: Option[UUID]): ConnectionIO[Int] =
    sql"""UPDATE party SET parent_party_id = $parent WHERE id = $child""".update.run
}
