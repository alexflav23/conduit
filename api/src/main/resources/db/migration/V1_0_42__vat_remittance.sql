-- M13-VAT.3 — VAT remittance: paying a tax authority depletes the accrued exposure. Immutable + deterministic like
-- everything else: DR VAT:<entity> / CR BANK:<entity>, the transfer id deterministic from the remittance id, a
-- tax.vat.remitted event. The per-jurisdiction VAT exposure is then a reproducible projection over immutable rows:
--   outstanding(entity, jurisdiction, period) = Σ recognised vat − Σ reversed vat − Σ remitted
-- and that per-entity outstanding ties to the VAT:<entity> ledger balance (the reconciliation control).
CREATE TABLE vat_remittance (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id      UUID NOT NULL REFERENCES entity(id),
    jurisdiction   CHAR(2) NOT NULL,
    period_key     TEXT NOT NULL,                 -- the VAT period being paid, e.g. '2026-06'
    amount         NUMERIC(18,4) NOT NULL,
    currency       CHAR(3) NOT NULL,
    reference      TEXT,                           -- the authority's payment/return reference
    tb_transfer_id NUMERIC(39,0),
    actor          TEXT,
    remitted_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX vat_remittance_idx ON vat_remittance (entity_id, jurisdiction, period_key);
