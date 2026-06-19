-- S2.2 lots: MRPeasy production/purchase lots captured as a queryable, auditable staging table (the supply-in
-- cost basis: each lot's per-unit item_cost + the PO it came from). Kept as staging — the careful promotion into
-- lot_batch (the specific-id COGS table) is a deliberate follow-on, because it must reconcile with the already-
-- costed fleet without double-counting. The raw lot is already durably in ingest_record; this makes it usable.
CREATE TABLE mrpeasy_lot_raw (
    lot_id        TEXT PRIMARY KEY,
    code          TEXT,
    item_code     TEXT,
    item_cost     NUMERIC(18, 4),
    total_cost    NUMERIC(18, 4),
    quantity      NUMERIC(18, 4),
    status        TEXT,
    po_code       TEXT,
    received_date DATE,
    first_seen    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX mrpeasy_lot_raw_item_idx ON mrpeasy_lot_raw (item_code);
CREATE INDEX mrpeasy_lot_raw_po_idx ON mrpeasy_lot_raw (po_code);
