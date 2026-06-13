-- M-Assurance B (doc 30 L9, the edit ⊆ view corollary the authz matrix enforces): V1_0_61 gave the CEO
-- view:tax_* but with NO layers ('{}'), so the projection showed it only unclassified fields while its
-- approve grant governs {volume,commercial,pii}. The CEO was approving tax governance it could not actually
-- SEE at the layers it signs off. Align the view layers to the approve layers, per object.
UPDATE permission v
SET viewable_layers = a.viewable_layers
FROM permission a, role r
WHERE v.role_id = r.id AND a.role_id = r.id AND r.name = 'ceo'
  AND v.object_type = a.object_type AND v.action = 'view' AND a.action = 'approve'
  AND v.object_type IN ('tax_rate', 'tax_regime', 'tax_routing')
  AND v.viewable_layers = '{}';
