-- Shadow-validation harness (doc 33 §5). Before Conduit becomes system of record it runs in shadow mode and a
-- battery of checks compares its computed reality (revenue, COGS/margin, invoices, backlog, ledger) against the
-- source-stated figures and against internal integrity invariants, surfacing every discrepancy into a TRIAGE
-- queue. Cutover gate (doc 33 §5): a sustained window with zero open money/unit findings. Complements the
-- period-close `reconciliation` table (aggregate, period-scoped) with per-record findings a human works to zero.

CREATE TABLE shadow_validation_run (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_by     UUID,
    shadow_mode    BOOLEAN NOT NULL DEFAULT false,   -- was hypervolt.shadow on when this ran
    checks_run     INTEGER NOT NULL DEFAULT 0,
    total_findings INTEGER NOT NULL DEFAULT 0,
    summary        JSONB NOT NULL DEFAULT '{}'        -- per-check + per-severity counts
);

CREATE TABLE shadow_finding (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    check_code  TEXT NOT NULL,                        -- e.g. cogs_without_revenue | order_header_vs_lines | backlog_identity
    severity    TEXT NOT NULL,                        -- critical | high | medium | low | info
    scope_type  TEXT NOT NULL,                        -- order | dispatch | serial | entity | recognition
    scope_id    TEXT NOT NULL,                        -- the natural key of the thing in question
    entity_id   UUID,
    expected    NUMERIC(18,4),                        -- source-stated / invariant-expected (NULL for non-numeric)
    actual      NUMERIC(18,4),                        -- Conduit-computed
    variance    NUMERIC(18,4),
    currency    TEXT,
    detail      JSONB NOT NULL DEFAULT '{}',
    status      TEXT NOT NULL DEFAULT 'open',         -- open | investigating | accepted | resolved
    note        TEXT,
    run_id      UUID REFERENCES shadow_validation_run(id),
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_by UUID,
    resolved_at TIMESTAMPTZ,
    UNIQUE (check_code, scope_type, scope_id)         -- one live finding per (check, thing); re-runs upsert
);
CREATE INDEX shadow_finding_status_idx ON shadow_finding (status, severity);
CREATE INDEX shadow_finding_check_idx  ON shadow_finding (check_code, status);

-- RBAC: finance/auditor/admin can view + triage shadow findings (the cutover gate is a finance/audit concern).
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'shadow_validation', 'view', NULL, '{commercial,profitability}', '{}', 'all' FROM role WHERE name IN ('finance','auditor','admin','ceo');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'shadow_validation', 'edit', NULL, '{commercial,profitability}', '{commercial}', 'all' FROM role WHERE name IN ('finance','admin');
