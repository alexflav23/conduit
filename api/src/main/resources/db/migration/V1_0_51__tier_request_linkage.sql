-- M-Pricing tail (doc 24 §3/§6): no-typed-prices enforcement + tier-request order linkage. The "ADLP exception" hold
-- no longer arises from a typed price (placement now REJECTS any non-tier price) — it arises from an order placed
-- against a DRAFT price agreement (a pending tier request). The adlp_exception row remains the desk workflow artifact
-- (worklist, narrative, layer projection); it now carries the agreement it is waiting on. The decision IS the
-- agreement activation (doc 24 §6.2), which releases + re-quotes the linked orders.
ALTER TABLE adlp_exception ADD COLUMN agreement_id UUID REFERENCES price_agreement(id);
CREATE INDEX adlp_exception_agreement_idx ON adlp_exception (agreement_id);
