-- M-Period slice 1 (spec doc 32): the group reporting calendar — the authoritative GROUP periods every
-- operating entity closes into. US GAAP (ASC 810) requires coterminous period-ends for consolidation; large
-- groups force a common group close rather than let entities drift. A group period for a key cannot LOCK
-- until every operating entity's accounting_period for that key is locked (the roll-up gate, enforced in
-- PeriodCloseService.closeGroup). scope = 'group'; entity periods keep scope = 'entity' (doc 02).
CREATE TABLE reporting_calendar (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    period_key  TEXT NOT NULL UNIQUE,          -- '2026-Q2', '2026-06' …
    period_from DATE NOT NULL,
    period_to   DATE NOT NULL,
    status      TEXT NOT NULL DEFAULT 'open',  -- open | closed | locked (the GROUP roll-up)
    locked_by   UUID,
    locked_at   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- view:accounting_period already gates the close board (doc 14 §6); the investigation view reuses it. The
-- group calendar is visible to the same roles that see the close board.
INSERT INTO permission (role_id, object_type, action, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'reporting_calendar', 'view', '{volume,commercial}'::text[], '{}'::text[], 'all'
FROM role r
WHERE r.name IN ('admin', 'ceo', 'finance', 'auditor')
  AND NOT EXISTS (SELECT 1 FROM permission p
                  WHERE p.role_id = r.id AND p.object_type = 'reporting_calendar' AND p.action = 'view');
