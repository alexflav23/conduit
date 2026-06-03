-- Commission: first-class schemes with validity windows + team/channel/country assignments (doc 02 §J).

CREATE TABLE sales_agent (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID REFERENCES app_user(id),
    name         TEXT NOT NULL,
    role         TEXT,
    team_id      UUID REFERENCES team(id),
    channel_id   UUID,
    market_scope UUID[] NOT NULL DEFAULT '{}',
    entity_scope UUID[] NOT NULL DEFAULT '{}',
    targets      JSONB NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE commission_scheme (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                TEXT NOT NULL,
    basis               TEXT NOT NULL DEFAULT 'gross_margin',
    rate_pct            NUMERIC(7,4) NOT NULL,
    tiers               JSONB,
    product_modifiers   JSONB,
    discount_modifier   JSONB,
    exception_treatment TEXT NOT NULL DEFAULT 'zero',
    valid_from          TIMESTAMPTZ NOT NULL,
    valid_to            TIMESTAMPTZ,
    status              TEXT NOT NULL DEFAULT 'active',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE commission_scheme_assignment (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id  UUID NOT NULL REFERENCES commission_scheme(id) ON DELETE CASCADE,
    team_id    UUID,
    channel_id UUID,
    market_id  UUID,
    entity_id  UUID,
    UNIQUE (scheme_id, team_id, channel_id, market_id, entity_id)
);
CREATE INDEX commission_assignment_scheme_idx ON commission_scheme_assignment (scheme_id);

CREATE TABLE commission_period (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         TEXT UNIQUE NOT NULL,
    cadence      TEXT NOT NULL DEFAULT 'quarterly',
    period_start DATE NOT NULL,
    period_end   DATE NOT NULL,
    status       TEXT NOT NULL DEFAULT 'open'
);

CREATE TABLE commission_entry (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id             UUID REFERENCES sales_agent(id),
    scheme_id            UUID REFERENCES commission_scheme(id),
    commission_period_id UUID REFERENCES commission_period(id),
    order_id             UUID,
    order_line_id        UUID,
    basis_amount         NUMERIC(18,4) NOT NULL,
    rate_applied         NUMERIC(7,4) NOT NULL,
    amount               NUMERIC(18,4) NOT NULL,
    currency             CHAR(3) NOT NULL,
    kind                 TEXT NOT NULL DEFAULT 'accrual',
    status               TEXT NOT NULL DEFAULT 'pending',
    tb_transfer_id       TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX commission_entry_agent_idx ON commission_entry (agent_id, status);
