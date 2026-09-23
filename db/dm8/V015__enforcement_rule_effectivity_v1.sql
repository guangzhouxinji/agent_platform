DROP INDEX ix_enf_local_rule_release_status;
ALTER TABLE enf_local_rule_release MODIFY effective_from NULL;
ALTER TABLE enf_local_rule_release ADD effectivity_status VARCHAR(80) DEFAULT 'EXPLICIT_EFFECTIVE_DATE' NOT NULL;
ALTER TABLE enf_local_rule_release ADD effectivity_basis VARCHAR(1000) DEFAULT 'LEGACY_EXPLICIT_EFFECTIVE_DATE_FIELD' NOT NULL;
ALTER TABLE enf_local_rule_release ADD CONSTRAINT ck_enf_local_rule_effectivity
    CHECK (effectivity_status IN ('EXPLICIT_EFFECTIVE_DATE','NO_EXPLICIT_EFFECTIVE_DATE'));
CREATE INDEX ix_enf_local_rule_release_status ON enf_local_rule_release
    (release_status, effectivity_status, effective_from, approved_at);
