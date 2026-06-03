-- SOX/ICFR control register, control runs, reconciliations and the close checklist (doc 02 §N, doc 14 §4-6).
-- Tables are foundational in M1; their behaviour (re-perform, reconciliation engine, close board) lands in M13b.

CREATE TABLE control (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code           TEXT UNIQUE NOT NULL,
    name           TEXT NOT NULL,
    objective      TEXT,
    assertion      TEXT[] NOT NULL DEFAULT '{}',
    type           TEXT NOT NULL,
    frequency      TEXT NOT NULL,
    automated      BOOLEAN NOT NULL DEFAULT false,
    owner_user_id  UUID,
    evidence_query TEXT,
    status         TEXT NOT NULL DEFAULT 'active',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control_run (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    control_id UUID NOT NULL REFERENCES control(id),
    run_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    result     TEXT NOT NULL,
    detail     JSONB,
    period_id  UUID REFERENCES accounting_period(id)
);

CREATE TABLE reconciliation (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type          TEXT NOT NULL,
    period_id     UUID NOT NULL REFERENCES accounting_period(id),
    scope         JSONB,
    expected      NUMERIC(18,4),
    actual        NUMERIC(18,4),
    currency      CHAR(3),
    variance      NUMERIC(18,4),
    status        TEXT NOT NULL DEFAULT 'open',
    signed_off_by UUID,
    signed_off_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE period_close_task (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    period_id    UUID NOT NULL REFERENCES accounting_period(id),
    name         TEXT NOT NULL,
    sequence     INTEGER NOT NULL,
    status       TEXT NOT NULL DEFAULT 'pending',
    done_by      UUID,
    evidence_ref JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
