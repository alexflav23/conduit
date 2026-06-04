-- Auto-PO proposer (closing the loop to real-time). Each H6Q recompute can diff forward demand against the firm
-- commitment + available stock, and auto-propose a PO delta — but ONLY within the time-fence headroom. Anything
-- beyond the movable window is recorded as blocked and raises a divergence warning (the frozen/over-flex gate).
CREATE TABLE po_proposal (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supplier_id        UUID NOT NULL REFERENCES supplier(id),
    product_variant_id UUID NOT NULL REFERENCES product_variant(id),
    target_date        DATE NOT NULL,
    demand_qty         INTEGER NOT NULL,   -- H6Q forward demand for the SKU/period
    committed_qty      INTEGER NOT NULL,   -- firm PO already placed
    available_qty      INTEGER NOT NULL,   -- finished-goods on hand (real-time)
    net_need           INTEGER NOT NULL,   -- max(demand - committed - available, 0)
    proposed_delta     INTEGER NOT NULL,   -- the part of net_need that fits within the headroom (auto-committable)
    blocked_qty        INTEGER NOT NULL,   -- the part beyond the gate (needs escalation; raises a warning)
    zone               TEXT NOT NULL,      -- the fence zone of the target week
    status             TEXT NOT NULL DEFAULT 'proposed', -- proposed | committed | dismissed
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (supplier_id, product_variant_id, target_date)
);
CREATE INDEX po_proposal_open_idx ON po_proposal (status, created_at DESC) WHERE status = 'proposed';
