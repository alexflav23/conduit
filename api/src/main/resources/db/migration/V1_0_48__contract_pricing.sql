-- M-Pricing slice 1 (doc 24 §2/§7): contract & volume-tiered pricing — the price_agreement container, the
-- multi-customer scope, tier bands (evolving price_rule), the governed product_class, and the open_list back-fill
-- so nothing regresses. FACTS only: validity is begin/end timestamps; lifecycle / contract-year / renewal are
-- derived projections, never stored (doc 24 §2 modelling discipline).

CREATE TABLE price_agreement (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              TEXT NOT NULL,
    surface           TEXT NOT NULL DEFAULT 'customer',          -- customer | inter_entity
    currency          CHAR(3) NOT NULL,
    applies_to        TEXT NOT NULL DEFAULT 'open_list',         -- open_list | customer_set | segment | sector
    base_volume_basis TEXT NOT NULL DEFAULT 'per_order',         -- per_order | cumulative_prospective | cumulative_retrospective
    valid_from        TIMESTAMPTZ NOT NULL DEFAULT now(),        -- THE validity window (begin); lifecycle is DERIVED
    valid_to          TIMESTAMPTZ,                               -- end (NULL = open-ended)
    terms             JSONB NOT NULL DEFAULT '{}',               -- min_commitment_units / term_months / renewal_type — descriptive INPUTS
    status            TEXT NOT NULL DEFAULT 'draft',             -- governance of the row: draft | active | superseded
    version           INTEGER NOT NULL DEFAULT 1,
    renews_from       UUID REFERENCES price_agreement(id),       -- optional recorded link (a fact, not a status)
    justification     TEXT,                                      -- the tier-request rationale (doc 24 §6)
    proposed_by       UUID,                                      -- maker
    approved_by       UUID,                                      -- checker (≠ proposed_by, doc 05 §4)
    approved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX price_agreement_resolution_idx
    ON price_agreement (surface, applies_to, currency, status, valid_from DESC);

-- Which parties an agreement is valid for (group aggregation, doc 24 §4). open_list agreements name no parties.
CREATE TABLE price_agreement_customer (
    agreement_id UUID NOT NULL REFERENCES price_agreement(id) ON DELETE CASCADE,
    party_id     UUID NOT NULL REFERENCES party(id),
    PRIMARY KEY (agreement_id, party_id)
);
CREATE INDEX price_agreement_customer_party_idx ON price_agreement_customer (party_id);

-- product_class: the governed dimension the qualifying/applies filters + contract_volume use (doc 24 §4.5),
-- replacing the is_serialised proxy. charger | accessory | cable | spare | bundle. Back-fill per the precision
-- ground truth: a charger SKU contains 'hv3' (e.g. HV3PROAA…); everything else defaults to accessory.
ALTER TABLE product_variant ADD COLUMN product_class TEXT NOT NULL DEFAULT 'accessory';
UPDATE product_variant SET product_class = 'charger' WHERE lower(sku) LIKE '%hv3%';

-- price_rule becomes the tier (band) row: it belongs to an agreement and carries a band ceiling. min_qty is the
-- band floor (from_qty); up_to_qty is the ceiling (NULL = open-ended). The customer scope moves onto the agreement.
ALTER TABLE price_rule ADD COLUMN price_agreement_id UUID REFERENCES price_agreement(id);
ALTER TABLE price_rule ADD COLUMN up_to_qty INTEGER;

-- order_line gains the contract reference (price_rule_id — the band — already present).
ALTER TABLE order_line ADD COLUMN price_agreement_id UUID REFERENCES price_agreement(id);

-- Back-fill: wrap every existing customer price_rule into one open_list agreement per currency, so today's
-- standard-list behaviour IS an open_list agreement and nothing regresses (doc 24 §2).
INSERT INTO price_agreement (name, surface, currency, applies_to, base_volume_basis, status)
SELECT DISTINCT 'Standard list (' || currency || ')', 'customer', currency, 'open_list', 'per_order', 'active'
FROM price_rule WHERE surface = 'customer';

UPDATE price_rule pr SET price_agreement_id = pa.id
FROM price_agreement pa
WHERE pa.applies_to = 'open_list' AND pa.surface = 'customer' AND pa.currency = pr.currency
  AND pr.surface = 'customer' AND pr.price_agreement_id IS NULL;
