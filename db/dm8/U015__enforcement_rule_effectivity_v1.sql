DROP INDEX ix_enf_local_rule_release_status;
ALTER TABLE enf_local_rule_release DROP CONSTRAINT ck_enf_local_rule_effectivity;
ALTER TABLE enf_local_rule_release MODIFY effective_from NOT NULL;
ALTER TABLE enf_local_rule_release DROP COLUMN effectivity_basis;
ALTER TABLE enf_local_rule_release DROP COLUMN effectivity_status;
CREATE INDEX ix_enf_local_rule_release_status ON enf_local_rule_release (release_status, effective_from);
