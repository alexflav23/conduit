-- Materialise the RMA units HubSpot knows about but the MRPeasy ledger never had: the V2 units that failed under
-- warranty, and the warranty-replacement stock (mostly never tracked as sales). These are REAL serials from real
-- RMA tickets, so they belong in the ledger with clear provenance — generation-classified, source-flagged. The
-- warranty WINDOW on a V2 stays NULL (V2 activation dates aren't in any ingested source — flagged, not faked).

-- Provenance on a serial: NULL = MRPeasy snapshot; 'hubspot_rma' = materialised from an RMA ticket.
ALTER TABLE serial_unit ADD COLUMN source TEXT;

-- Placeholder variant for a materialised V3 replacement whose exact model isn't known (the serial is real; the SKU
-- isn't). V2 units attach to the existing hv-2.0-uwt-t2 variant.
INSERT INTO product_variant (family_id, sku, generation, is_serialised, product_class, status)
SELECT family_id, 'HV3-RMA-UNSPECIFIED', 'v3', true, 'charger', 'active'
FROM product_variant WHERE generation = 'v3' LIMIT 1
ON CONFLICT (sku) DO NOTHING;
