-- S2 company↔company hierarchy: the AUTHORITATIVE HubSpot parent/child company links (e.g. CEF (Primary) → its
-- ~392 branch companies), landed for audit and then applied to party.parent_party_id by BranchLinkService.
-- Distinct from the name-heuristic branch detection (IgnitionService.detectBranches, "PARENT (location)") — this
-- fills the gaps the heuristic misses (branches whose names don't follow the pattern) WITHOUT overriding an
-- existing parent. Idempotent on the child company id (a child has one parent).
CREATE TABLE hubspot_company_relation (
    child_company_id  TEXT PRIMARY KEY,
    parent_company_id TEXT NOT NULL,
    first_seen        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX hubspot_company_relation_parent_idx ON hubspot_company_relation (parent_company_id);
