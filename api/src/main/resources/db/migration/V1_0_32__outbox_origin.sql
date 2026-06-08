-- M13-Void.5d — lineage precision (doc 03 §1). Every event records its ORIGIN (the actor/service that emitted
-- it: a user, a domain service, or a consumer) so the collection-ledger timeline shows true causality — who/what
-- caused each step, not just that it happened. `occurred_at` is already a UTC instant (timezone-complete); this
-- adds the missing "from whom". Defaults to 'system' for historical rows.
ALTER TABLE outbox_event ADD COLUMN origin TEXT NOT NULL DEFAULT 'system';
