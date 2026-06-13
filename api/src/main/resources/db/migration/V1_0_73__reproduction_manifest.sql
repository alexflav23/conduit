-- M-Assurance D (spec doc 29): reproducibility, proven. A manifest records the canonical digest of the
-- money tables' id-independent aggregates (count + sum per key) at a given ingest git SHA. The law: same
-- data + same code ⇒ one digest. CTRL-REPRO surfaces any (scope, git_sha) that has produced MORE THAN ONE
-- distinct digest — i.e. the same code+data point reproduced differently, which is non-determinism or drift.
-- This retroactively settles the 2026-06-12 cross-machine question and makes future drift visible in git
-- (the refresher commits ingest/fingerprint.json per run).
CREATE TABLE reproduction_manifest (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope      TEXT NOT NULL,                 -- 'ledger' (the money tables) etc.
    git_sha    TEXT NOT NULL,                 -- the ingest snapshot SHA the data was built from
    digest     TEXT NOT NULL,                 -- sha-256 of the canonical aggregate lines
    line_count BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX reproduction_manifest_idx ON reproduction_manifest (scope, git_sha, created_at DESC);

INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-REPRO', 'Same data + code reproduces one digest',
   'No (scope, ingest SHA) has produced more than one distinct fingerprint — the books reproduce bit-identically from the same code and data.',
   '{accuracy,completeness}', 'detective', 'continuous', true,
   'SELECT count(*) FROM (SELECT scope, git_sha FROM reproduction_manifest
                          GROUP BY scope, git_sha HAVING count(DISTINCT digest) > 1) v')
ON CONFLICT (code) DO NOTHING;
