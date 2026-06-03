-- CRM parties (doc 02 §C) + orders/fulfilment (doc 02 §F). One `party`; classification is data.

CREATE TABLE party_type (
    code            TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    is_organization BOOLEAN NOT NULL,
    required_profiles TEXT[] NOT NULL DEFAULT '{}'
);
INSERT INTO party_type (code, name, is_organization, required_profiles) VALUES
    ('individual','Individual', false, '{}'),
    ('installer','Installer', true, '{}'),
    ('wholesaler','Wholesaler', true, '{billing,credit}'),
    ('branch','Branch', true, '{}'),
    ('distributor','Distributor', true, '{billing,credit}'),
    ('energy_partner','Energy partner', true, '{billing}'),
    ('fleet','Fleet', true, '{billing}'),
    ('oem','OEM', true, '{billing}'),
    ('other','Other', true, '{}');

CREATE TABLE pricing_tier (
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL
);

CREATE TABLE party (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name           TEXT NOT NULL,
    legal_name             TEXT,
    party_type             TEXT NOT NULL REFERENCES party_type(code),
    is_organization        BOOLEAN NOT NULL,
    parent_party_id        UUID REFERENCES party(id),
    status                 TEXT NOT NULL DEFAULT 'active',
    default_entity_id      UUID REFERENCES entity(id),
    channel_id             UUID,
    market_id              UUID,
    segment                TEXT,
    pricing_tier_id        UUID REFERENCES pricing_tier(id),
    preferred_locale       TEXT,
    roles                  TEXT[] NOT NULL DEFAULT '{}',
    customer_po_required   BOOLEAN NOT NULL DEFAULT false,
    account_manager_user_id UUID,
    owner_user_id          UUID,
    external_refs          JSONB NOT NULL DEFAULT '{}',
    attributes             JSONB NOT NULL DEFAULT '{}',
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX party_parent_idx ON party (parent_party_id);
CREATE INDEX party_type_idx ON party (party_type);

CREATE TABLE address (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type TEXT NOT NULL,
    owner_id   UUID NOT NULL,
    type       TEXT NOT NULL,
    line1      TEXT, line2 TEXT, city TEXT, region TEXT, postcode TEXT, country CHAR(2),
    is_default BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE contact (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    party_id          UUID NOT NULL REFERENCES party(id),
    first_name        TEXT, last_name TEXT, role TEXT,
    email             CITEXT, phone TEXT, phone_country TEXT,
    is_primary        BOOLEAN NOT NULL DEFAULT false,
    marketing_consent BOOLEAN NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX contact_party_idx ON contact (party_id);

CREATE TABLE billing_profile (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    party_id                UUID NOT NULL REFERENCES party(id),
    billing_name            TEXT NOT NULL,
    bill_to_address_id      UUID REFERENCES address(id),
    tax_registration_number TEXT,
    tax_regime_default      TEXT REFERENCES tax_regime(code),
    currency                CHAR(3) NOT NULL,
    payment_terms_days      INTEGER NOT NULL,
    invoice_locale          TEXT,
    bills_to_party_id       UUID REFERENCES party(id),
    status                  TEXT NOT NULL DEFAULT 'active'
);
CREATE INDEX billing_profile_party_idx ON billing_profile (party_id);

CREATE TABLE credit_profile (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    party_id     UUID NOT NULL REFERENCES party(id),
    credit_limit NUMERIC(18,4) NOT NULL,
    currency     CHAR(3) NOT NULL,
    terms_days   INTEGER NOT NULL,
    policy       TEXT NOT NULL DEFAULT 'block',
    scope        TEXT NOT NULL DEFAULT 'self'
);
CREATE INDEX credit_profile_party_idx ON credit_profile (party_id);

CREATE SEQUENCE order_no_seq START 1000;

CREATE TABLE "order" (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_no            TEXT UNIQUE NOT NULL,
    type                TEXT NOT NULL,
    entity_id           UUID REFERENCES entity(id),
    sold_to_party_id    UUID NOT NULL REFERENCES party(id),
    bill_to_party_id    UUID NOT NULL REFERENCES party(id),
    contact_id          UUID REFERENCES contact(id),
    customer_po_number  TEXT,
    channel_id          UUID,
    market_id           UUID,
    agent_id            UUID,
    status              TEXT NOT NULL,
    adlp_category       TEXT NOT NULL DEFAULT 'standard',
    txn_currency        CHAR(3) NOT NULL,
    subtotal_ex_vat     NUMERIC(18,4) NOT NULL DEFAULT 0,
    vat_total           NUMERIC(18,4) NOT NULL DEFAULT 0,
    total_inc_vat       NUMERIC(18,4) NOT NULL DEFAULT 0,
    payment_method      TEXT NOT NULL,
    order_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    requested_delivery  DATE,
    amend_cutoff        TIMESTAMPTZ,
    dispatched_at       TIMESTAMPTZ,
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX order_sold_to_idx ON "order" (sold_to_party_id, order_date DESC);
CREATE INDEX order_status_idx ON "order" (status);

CREATE TABLE order_line (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id           UUID NOT NULL REFERENCES "order"(id) ON DELETE CASCADE,
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    qty                INTEGER NOT NULL,
    unit_price_ex_vat  NUMERIC(18,4) NOT NULL,
    discount_pct       NUMERIC(5,2) NOT NULL DEFAULT 0,
    tax_regime         TEXT,
    vat_amount         NUMERIC(18,4) NOT NULL DEFAULT 0,
    line_total_inc_vat NUMERIC(18,4) NOT NULL DEFAULT 0,
    price_rule_id      UUID,
    adlp_category      TEXT NOT NULL DEFAULT 'standard',
    qty_allocated      INTEGER NOT NULL DEFAULT 0,
    qty_dispatched     INTEGER NOT NULL DEFAULT 0,
    is_scheduled       BOOLEAN NOT NULL DEFAULT false,
    status             TEXT NOT NULL DEFAULT 'open',
    promised_date      DATE
);
CREATE INDEX order_line_order_idx ON order_line (order_id);

CREATE TABLE delivery_tranche (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_line_id  UUID NOT NULL REFERENCES order_line(id) ON DELETE CASCADE,
    seq            INTEGER NOT NULL,
    qty            INTEGER NOT NULL,
    requested_date DATE NOT NULL,
    qty_allocated  INTEGER NOT NULL DEFAULT 0,
    qty_dispatched INTEGER NOT NULL DEFAULT 0,
    status         TEXT NOT NULL DEFAULT 'scheduled',
    dispatch_id    UUID,
    UNIQUE (order_line_id, seq)
);

CREATE TABLE order_amendment (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id      UUID NOT NULL REFERENCES "order"(id),
    actor_user_id UUID,
    before        JSONB,
    after         JSONB,
    reason        TEXT,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX order_amendment_order_idx ON order_amendment (order_id);

CREATE TABLE adlp_exception (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID REFERENCES "order"(id),
    order_line_id       UUID REFERENCES order_line(id),
    requested_price     NUMERIC(18,4),
    requested_discount_pct NUMERIC(5,2),
    justification       TEXT,
    volume_expectation  INTEGER,
    volume_denomination TEXT,
    strategic_importance TEXT,
    status              TEXT NOT NULL DEFAULT 'pending_ceo',
    approved_by         UUID,
    approval_memo_ref   TEXT,
    decided_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX adlp_exception_order_idx ON adlp_exception (order_id);
