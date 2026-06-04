-- Activation ingest + warranty provision (doc 02 §G, doc 04 §Serial/§Warranty). First-write-wins per
-- serial; the warranty clock starts at activation; the provision is per-unit at the specific batch cost.

CREATE TABLE activation (
    serial              TEXT PRIMARY KEY,
    placement_id        UUID NOT NULL,
    placement_version   INTEGER NOT NULL,
    installer_user_id   TEXT,
    installer_email     TEXT,
    installer_name      TEXT,
    placement_name      TEXT,
    placement_country   TEXT,
    placement_created_at TIMESTAMPTZ,
    charger_model       TEXT,
    charger_mac         TEXT,
    charger_keycloak_id TEXT,
    activated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Mandatory statutory term by jurisdiction (consumer law). product_family_id NULL = all families.
CREATE TABLE legal_warranty (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    jurisdiction      CHAR(2) NOT NULL,
    product_family_id UUID REFERENCES product_family(id),
    statutory_months  INTEGER NOT NULL,
    basis             TEXT,
    effective_from    DATE NOT NULL DEFAULT '2000-01-01',
    effective_to      DATE
);
INSERT INTO legal_warranty (jurisdiction, product_family_id, statutory_months, basis)
    VALUES ('GB', NULL, 24, 'Consumer Rights Act 2015');

-- Provisioning assumption (versioned, audited GAAP-relevant input).
CREATE TABLE warranty_rate (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_family_id  UUID REFERENCES product_family(id),
    generation         TEXT,
    provision_rate_pct NUMERIC(7,4),
    provision_per_unit NUMERIC(18,4),
    effective_from     DATE NOT NULL DEFAULT '2000-01-01',
    effective_to       DATE
);
INSERT INTO warranty_rate (product_family_id, generation, provision_rate_pct)
    VALUES (NULL, NULL, 5.0000);

CREATE TABLE warranty_extension (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    serial_unit_id UUID REFERENCES serial_unit(id),
    order_line_id  UUID REFERENCES order_line(id),
    extra_months   INTEGER NOT NULL,
    source         TEXT,
    ref            TEXT
);

CREATE TABLE warranty_provision (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    serial_unit_id      UUID NOT NULL REFERENCES serial_unit(id),
    entity_id           UUID,
    lot_batch_id        UUID REFERENCES lot_batch(id),
    warranty_start      DATE NOT NULL,
    warranty_end        DATE NOT NULL,
    estimated_provision NUMERIC(18,4) NOT NULL,
    currency            CHAR(3) NOT NULL,
    released_to_date    NUMERIC(18,4) NOT NULL DEFAULT 0,
    consumed_by_claims  NUMERIC(18,4) NOT NULL DEFAULT 0,
    outstanding         NUMERIC(18,4) NOT NULL,
    status              TEXT NOT NULL DEFAULT 'open',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (serial_unit_id)
);
CREATE INDEX warranty_provision_entity_idx ON warranty_provision (entity_id, status);

CREATE TABLE warranty_claim (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    serial_unit_id UUID NOT NULL REFERENCES serial_unit(id),
    raised_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    description    TEXT,
    cost           NUMERIC(18,4) NOT NULL,
    currency       CHAR(3) NOT NULL,
    resolution     TEXT,
    status         TEXT NOT NULL DEFAULT 'open',
    rma_id         UUID
);
