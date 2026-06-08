-- M13-Pay.2 — inbound Stripe webhook queue (doc 13 §payments). The PUBLIC webhook endpoint verifies the
-- signature and records the raw event here (idempotent on Stripe's event id — the API never touches the ledger).
-- A consumer-side drain settles each row against TigerBeetle via PaymentService (which is itself idempotent on
-- the Stripe ref) and marks it processed. This keeps the ledger write out of the API and gives an audit trail
-- of every Stripe event Conduit received, whether or not it moved money.
CREATE TABLE stripe_event (
    id           TEXT PRIMARY KEY,          -- Stripe's event id (evt_...); the idempotency key
    event_type   TEXT NOT NULL,
    payload      JSONB NOT NULL,
    status       TEXT NOT NULL DEFAULT 'received',  -- received / processed / ignored / failed
    result       TEXT,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);
CREATE INDEX stripe_event_pending_idx ON stripe_event (received_at) WHERE status = 'received';
