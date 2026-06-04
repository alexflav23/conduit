-- M11-B — H6Q-updated propagation + notifications (doc 12 §2.6 + doc 10 §B Notifications).
-- When coverage recomputes, forward visibility has shifted — and people downstream care: account owners, the
-- exec, and external partners such as our contract manufacturer (Volex/Luxshare), whose supply commitments and
-- 6-month buffer (forecasting guide §1) ride on our forward demand. Every recompute emits
-- forecast.coverage.updated and fans out to matching subscriptions past a materiality threshold.

-- Who wants to hear about which H6Q events, on what channel, and how big a shift they care about.
CREATE TABLE notification_subscription (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             TEXT NOT NULL,
    subscriber_type  TEXT NOT NULL,                       -- user | stakeholder (external partner)
    subscriber_user_id UUID NULL,                         -- -> app_user when subscriber_type='user'
    channel          TEXT NOT NULL,                       -- in_app | email | webhook
    endpoint         TEXT NULL,                           -- email address / webhook URL for external channels
    event_types      TEXT[] NOT NULL DEFAULT '{}',        -- e.g. {forecast.coverage.updated, forecast.cycle.opened}
    scope_market_id  UUID NULL,                           -- NULL = all markets
    min_change_pct   NUMERIC(6,2) NOT NULL DEFAULT 0,     -- materiality: only notify when forward demand moves >= this
    active           BOOLEAN NOT NULL DEFAULT true,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX notification_subscription_active_idx ON notification_subscription (active) WHERE active;

-- A produced notification. For external channels (email/webhook) it is the outbound dispatch record — created
-- 'pending' and marked 'sent' by the delivery relay (the actual send is wired alongside the notifications model).
CREATE TABLE notification (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES notification_subscription(id),
    event_type      TEXT NOT NULL,
    subject         TEXT NOT NULL,
    body            TEXT NOT NULL,
    payload         JSONB NOT NULL DEFAULT '{}',
    status          TEXT NOT NULL DEFAULT 'pending',      -- pending | sent | failed
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ NULL
);
CREATE INDEX notification_sub_idx ON notification (subscription_id, created_at DESC);
CREATE INDEX notification_status_idx ON notification (status) WHERE status <> 'sent';

-- Seed the standing subscriptions: the contract manufacturer wants any material forward-visibility shift (>=10%)
-- on a webhook; the exec wants in-app on every shift. (Real endpoints/preferences are config.)
INSERT INTO notification_subscription (name, subscriber_type, channel, endpoint, event_types, min_change_pct) VALUES
    ('Contract manufacturer (Volex)', 'stakeholder', 'webhook', 'https://volex.example/hooks/h6q', '{forecast.coverage.updated}', 10.00),
    ('Exec — forward visibility', 'stakeholder', 'in_app', NULL, '{forecast.coverage.updated}', 0.00);
