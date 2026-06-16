-- entity_type is a closed enum (operating | procurement), not free text — enforced at the DB boundary to match the
-- Scala EntityType enum. A CHECK constraint (rather than a native PG enum) keeps it migration-friendly: introducing
-- a new type means adding a case to EntityType AND extending this constraint, in lockstep.
ALTER TABLE entity ADD CONSTRAINT entity_entity_type_chk CHECK (entity_type IN ('operating', 'procurement'));
