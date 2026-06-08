-- M13-Tax.5 — ICFR controls for the tax subsystem (doc 16 §8). Each evidence_query is re-performable SQL returning
-- the VIOLATION COUNT (0 = pass), run by ControlRunner and surfaced in the Auditability Center. These extend the
-- doc-14 control register. The byte-exact rate-table reproducibility (re-running determine over the snapshot) is
-- asserted by the integration suite; the SQL controls here check the retained-evidence + conservation invariants.

INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-TAX-VAT-CONSERVE', 'VAT conservation (line-vs-invoice)',
   'Σ tax_quote_line.line_tax_total == tax_quote.total_tax under the regime rounding policy (no penny created/lost).',
   '{valuation}', 'detective', 'continuous', true,
   'SELECT count(*) FROM (SELECT tq.id FROM tax_quote tq JOIN tax_quote_line tql ON tql.tax_quote_id = tq.id GROUP BY tq.id, tq.total_tax HAVING sum(tql.line_tax_total) <> tq.total_tax) v'),

  ('CTRL-TAX-REPRO', 'Tax-quote reproducibility evidence',
   'Every determination retains request + response snapshots and the snapshot provider matches the row (replayable).',
   '{valuation,accuracy}', 'detective', 'continuous', true,
   'SELECT count(*) FROM tax_quote WHERE request_snapshot IS NULL OR response_snapshot IS NULL OR response_snapshot->>''provider'' <> provider'),

  ('CTRL-TAX-EXT-EVIDENCE', 'External-quote evidence retained',
   'Every externally-determined quote keeps the vendor provider_ref + response (authority-of-record evidence).',
   '{completeness}', 'detective', 'continuous', true,
   'SELECT count(*) FROM tax_quote WHERE provider <> ''rate_table'' AND (provider_ref IS NULL OR response_snapshot IS NULL)'),

  ('CTRL-TAX-NEXUS-GATE', 'Nexus gating (external destination tax)',
   'No externally-determined US/CA destination tax is charged without a collects_tax registration / asserted nexus.',
   '{rights_obligations,completeness}', 'detective', 'continuous', true,
   'SELECT count(*) FROM tax_quote tq WHERE tq.provider <> ''rate_table'' AND tq.ship_to_jurisdiction IN (''US'',''CA'') AND tq.total_tax > 0 AND NOT EXISTS (SELECT 1 FROM tax_registration r WHERE r.entity_id = tq.entity_id AND r.jurisdiction = tq.ship_to_jurisdiction AND r.collects_tax AND (r.region IS NOT DISTINCT FROM tq.ship_to_region OR r.registration_kind = ''nexus''))')
ON CONFLICT (code) DO NOTHING;
