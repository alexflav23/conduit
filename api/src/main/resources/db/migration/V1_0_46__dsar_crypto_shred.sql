-- M-NFR.1 — GDPR right-to-erasure via CRYPTO-SHRED (doc 19 §B.3). Resolves the tension between GDPR erasure and
-- indefinite immutable financial/audit retention (SOX/PCAOB, doc 14): PII is encrypted with a per-subject data key
-- (DEK), the DEK is wrapped by a KMS-held key-encryption-key (KEK); erasure DESTROYS the DEK, so the ciphertext is
-- permanently undecryptable while the non-personal financial skeleton (amounts, dates, ids, ledger transfers) is
-- retained intact and still re-performs. PII never lives in long-retained event payloads — only the subject id does.

-- One DEK per data subject (a `party` of a person nature, or a contact), stored wrapped by the KEK.
CREATE TABLE pii_key (
    subject_id  UUID PRIMARY KEY,
    wrapped_dek TEXT,                              -- base64(KEK-wrapped DEK); NULL once shredded (the key is gone)
    status      TEXT NOT NULL DEFAULT 'active',    -- active | shredded
    shredded_at TIMESTAMPTZ,
    shredded_by UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The PII vault: personal fields stored as ciphertext (per-subject DEK, AES-GCM). The skeleton lives elsewhere.
CREATE TABLE pii_record (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id UUID NOT NULL,
    field      TEXT NOT NULL,                      -- 'display_name' | 'email' | 'phone' | ...
    ciphertext TEXT NOT NULL,                      -- base64(nonce || AES-GCM(DEK, plaintext))
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (subject_id, field)
);

-- The governed DSAR workflow (doc 19 §B.3.3) — maker-checker: requester ≠ the Data-Protection approver.
CREATE TABLE dsar_request (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id   UUID NOT NULL,
    kind         TEXT NOT NULL,                    -- access | erasure
    status       TEXT NOT NULL DEFAULT 'pending',  -- pending | approved | completed | rejected
    reason       TEXT,
    requested_by UUID,
    approved_by  UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at   TIMESTAMPTZ
);
CREATE INDEX dsar_request_subject_idx ON dsar_request (subject_id);

-- A re-performable control: a shredded key must have NO wrapped DEK (the erasure truly destroyed the key, doc 19
-- §B.3.1). >0 violations means an "erasure" left a recoverable key — a finding.
INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-PII-SHRED', 'Crypto-shred is irreversible',
   'Every shredded PII key has its wrapped DEK destroyed (NULL); erasure is not reversible.',
   '{rights_obligations,completeness}', 'detective', 'continuous', true,
   'SELECT count(*) FROM pii_key WHERE status = ''shredded'' AND wrapped_dek IS NOT NULL')
ON CONFLICT (code) DO NOTHING;

-- DSAR is a governed, audited action (doc 05 §5). data_protection/admin/ceo handle it; approval is maker-checker.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'dsar', 'view', NULL, '{}', '{}', 'all' FROM role WHERE name IN ('admin','ceo','finance','auditor');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'dsar', 'edit', NULL, '{}', '{}', 'all' FROM role WHERE name IN ('admin');
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT id, 'dsar', 'approve', NULL, '{}', '{}', 'all' FROM role WHERE name IN ('ceo','admin');
