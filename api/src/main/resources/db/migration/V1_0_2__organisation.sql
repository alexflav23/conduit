-- Organisation, currency, FX and accounting periods (doc 02 §A, doc 14 §2.4). Group presentation = USD.

CREATE TABLE currency (
    code        CHAR(3) PRIMARY KEY,
    name        TEXT NOT NULL,
    minor_units INTEGER NOT NULL,
    rounding    TEXT NOT NULL DEFAULT 'half_up'
);
INSERT INTO currency (code, name, minor_units) VALUES
    ('USD','US Dollar',2), ('GBP','Pound Sterling',2), ('EUR','Euro',2), ('CAD','Canadian Dollar',2),
    ('CHF','Swiss Franc',2), ('PLN','Polish Zloty',2), ('NOK','Norwegian Krone',2), ('SEK','Swedish Krona',2),
    ('DKK','Danish Krone',2), ('JPY','Japanese Yen',0), ('AUD','Australian Dollar',2),
    ('NZD','New Zealand Dollar',2), ('THB','Thai Baht',2);

-- Legal operating entity. Procurement topology is config (procurement_parent_id), not code.
CREATE TABLE entity (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                  TEXT NOT NULL,
    jurisdiction          CHAR(2) NOT NULL,
    functional_currency   CHAR(3) NOT NULL REFERENCES currency(code),
    entity_type           TEXT NOT NULL,
    group_parent_id       UUID REFERENCES entity(id),
    procurement_parent_id UUID REFERENCES entity(id),
    status                TEXT NOT NULL DEFAULT 'active',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Provenanced spot register (doc 14 §1.4). Every conversion records which row it used.
CREATE TABLE exchange_rate (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    base        CHAR(3) NOT NULL,
    quote       CHAR(3) NOT NULL,
    rate        NUMERIC(18,8) NOT NULL,
    rate_type   TEXT NOT NULL,
    as_of       DATE NOT NULL,
    source      TEXT,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (base, quote, rate_type, as_of)
);

-- Close & lock. No posting to a `locked` period (enforced at the ledger boundary).
CREATE TABLE accounting_period (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id    UUID NOT NULL REFERENCES entity(id),
    scope        TEXT NOT NULL,
    period_key   TEXT NOT NULL,
    reporting_tz TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'open',
    closed_by    UUID,
    closed_at    TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (entity_id, scope, period_key)
);
