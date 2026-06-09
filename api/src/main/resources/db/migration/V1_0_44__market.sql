-- M13-VAT.6 — the `market` table (doc 02 §A). Until now market_id was a bare UUID with no country; a market is the
-- place of supply, so it carries a `jurisdiction` (ISO country) + presentation currency. This lets order placement
-- resolve the seller-of-record entity from `selling_entity` by the market's jurisdiction, and lets cross-border tax
-- flow through the engine at quote/placement (not just at recognition).
CREATE TABLE market (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         TEXT UNIQUE NOT NULL,
    name         TEXT NOT NULL,
    jurisdiction CHAR(2) NOT NULL,        -- place of supply (ISO country)
    currency     CHAR(3) NOT NULL,
    status       TEXT NOT NULL DEFAULT 'active',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX market_jurisdiction_idx ON market (jurisdiction);

-- Year-1 seed: the UK market is the demo market id the desk + pricing already send (22222…), GB / GBP.
INSERT INTO market (id, code, name, jurisdiction, currency) VALUES
    ('22222222-2222-2222-2222-222222222222', 'UK', 'United Kingdom', 'GB', 'GBP')
ON CONFLICT (id) DO NOTHING;
