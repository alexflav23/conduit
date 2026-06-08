-- M13-VAT.1 — the entity-resolution config. Which Hypervolt operating entity is the seller-of-record (and the
-- registered taxpayer) for sales into a jurisdiction. Effective-dated + maker-checker (a change is a new dated row,
-- never an edit) so it is reproducible and governed. This single map expresses BOTH models: map DE→HV-UK today
-- (home-country serves the region) and remap DE→HV-GmbH when a local entity opens — purely configuration.
CREATE TABLE selling_entity (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    jurisdiction   CHAR(2) NOT NULL,                         -- place of supply / market
    entity_id      UUID NOT NULL REFERENCES entity(id),      -- the active operating entity (its functional_currency books the sale)
    effective_from DATE NOT NULL DEFAULT DATE '1970-01-01',
    effective_to   DATE,
    status         TEXT NOT NULL DEFAULT 'active',           -- draft/active/superseded (maker-checker)
    proposed_by    UUID,
    approved_by    UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX selling_entity_idx ON selling_entity (jurisdiction, effective_from DESC);

-- Year-1 seed: GB sells through the first GB operating entity (best-effort; tests/e2e set their own).
INSERT INTO selling_entity (jurisdiction, entity_id, status)
SELECT 'GB', e.id, 'active' FROM entity e
WHERE e.jurisdiction = 'GB' AND e.entity_type = 'operating'
  AND NOT EXISTS (SELECT 1 FROM selling_entity se WHERE se.jurisdiction = 'GB')
ORDER BY e.created_at
LIMIT 1;

-- Access (doc 05): admin proposes the entity map; CFO (ceo) approves; finance/auditor view. Structural config —
-- no money fields, so no field_layer_map entries.
INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'selling_entity', t.act, 'org_config', '{commercial}', '{commercial}', 'all'
FROM role r
CROSS JOIN (VALUES ('view'), ('create')) AS t(act)
WHERE r.name = 'admin';

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'selling_entity', 'approve', 'org_config', '{commercial}', '{commercial}', 'all'
FROM role r WHERE r.name = 'ceo';

INSERT INTO permission (role_id, object_type, action, section, viewable_layers, editable_layers, data_breadth)
SELECT r.id, 'selling_entity', 'view', 'org_config', '{commercial}', '{}', 'all'
FROM role r WHERE r.name IN ('finance', 'auditor', 'tax_specialist', 'ceo');
