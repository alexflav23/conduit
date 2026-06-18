-- Order golden record (topology, mirrors the account MDM): the Conduit order (order.id) is the master; the
-- MRPeasy order, customer PO, HubSpot deal, payments etc. are SOURCE identities linked to it via order_source_link.
-- conduit_ref is the Conduit-native order reference shown above the MRP code. Backfilled by IgnitionService.
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS conduit_ref text;

CREATE TABLE IF NOT EXISTS order_source_link (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  order_id      uuid NOT NULL REFERENCES "order"(id) ON DELETE CASCADE,
  source_system text NOT NULL,     -- mrpeasy | customer_po | hubspot_deal | payment | ...
  source_ref    text NOT NULL,
  source_detail text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (order_id, source_system, source_ref)
);
CREATE INDEX IF NOT EXISTS order_source_link_order_idx ON order_source_link(order_id);
CREATE INDEX IF NOT EXISTS order_conduit_ref_idx ON "order"(conduit_ref);
