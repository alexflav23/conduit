-- M13-VAT.5 — ICFR control for the VAT exposure (doc 16 §8). Re-performable SQL returning the violation count
-- (0 = pass). You cannot remit more VAT than you owe: per (entity, jurisdiction), Σ remitted must not exceed
-- net accrued (Σ recognised vat − Σ reversed vat). The projection↔ledger tie itself is proven by
-- VatRemittanceService.reconcile (it reads TigerBeetle, so it lives in the reconciliation flow, not a SQL control).
INSERT INTO control (code, name, objective, assertion, type, frequency, automated, evidence_query) VALUES
  ('CTRL-VAT-NO-OVER-REMIT', 'VAT not over-remitted',
   'Per (entity, jurisdiction), VAT remitted to the authority never exceeds net accrued (recognised − reversed).',
   '{valuation,rights_obligations}', 'detective', 'continuous', true,
   'SELECT count(*) FROM (
      SELECT a.entity_id, a.jur
      FROM (SELECT entity_id, vat_jurisdiction AS jur, SUM(vat) AS net FROM revenue_recognition
            WHERE vat_jurisdiction IS NOT NULL GROUP BY entity_id, vat_jurisdiction) a
      LEFT JOIN (SELECT rr.entity_id, rr.vat_jurisdiction AS jur, SUM(ivr.reversed_vat) AS rev
                 FROM invoice_reversal ivr JOIN revenue_recognition rr ON rr.dispatch_id = ivr.dispatch_id
                 WHERE rr.vat_jurisdiction IS NOT NULL GROUP BY rr.entity_id, rr.vat_jurisdiction) r
        ON r.entity_id = a.entity_id AND r.jur = a.jur
      LEFT JOIN (SELECT entity_id, jurisdiction AS jur, SUM(amount) AS rem FROM vat_remittance
                 GROUP BY entity_id, jurisdiction) m
        ON m.entity_id = a.entity_id AND m.jur = a.jur
      WHERE COALESCE(m.rem, 0) > a.net - COALESCE(r.rev, 0)
    ) over_remitted')
ON CONFLICT (code) DO NOTHING;
