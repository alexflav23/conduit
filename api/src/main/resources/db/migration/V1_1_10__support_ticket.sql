-- S2.1 support tickets: HubSpot service tickets as related lifecycle entities of the master account (the
-- "support ticket" the shadow-mode reframe named). Stored with the HubSpot company/contact ids (resolved to the
-- master party via account_source_link in the desk/correlation, same as deal_snapshot). Distinct from rma_ticket
-- (the replacement pipeline). Idempotent on the HubSpot ticket id.
CREATE TABLE support_ticket (
    ticket_ref TEXT PRIMARY KEY,
    subject    TEXT,
    status     TEXT,
    priority   TEXT,
    opened_at  DATE,
    company_id TEXT,
    contact_id TEXT,
    first_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX support_ticket_company_idx ON support_ticket (company_id);
