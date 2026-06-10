-- M9c (outbound symmetry, user spec): the same first-class tranche treatment on customer deliveries —
-- the outbound shipping fee is DEFINED PER TRANCHE (and conservingly allocated when a tranche ships across
-- several dispatches), with the same roll-forward balance snapshot on fulfilment.
ALTER TABLE delivery_tranche ADD COLUMN IF NOT EXISTS transport_mode TEXT NULL
    CHECK (transport_mode IS NULL OR transport_mode IN ('truck', 'rail', 'sea', 'air', 'courier'));
ALTER TABLE delivery_tranche ADD COLUMN IF NOT EXISTS carrier_ref TEXT NULL;
ALTER TABLE delivery_tranche ADD COLUMN IF NOT EXISTS freight_amount NUMERIC(14, 4) NOT NULL DEFAULT 0;
ALTER TABLE delivery_tranche ADD COLUMN IF NOT EXISTS freight_currency TEXT NOT NULL DEFAULT 'GBP';
ALTER TABLE delivery_tranche ADD COLUMN IF NOT EXISTS balance_after NUMERIC(14, 4) NULL;
