-- Flag which suppliers are contract manufacturers we auto-propose POs to. Every H6Q recompute refreshes the
-- auto-PO proposals for these suppliers, so the supply plan is never out of sync with forward demand.
ALTER TABLE supplier ADD COLUMN is_contract_manufacturer BOOLEAN NOT NULL DEFAULT false;
