DROP INDEX ix_enf_local_rule_release_status;
DROP TABLE enf_local_rule_release;
DROP TABLE enf_local_authorization;
ALTER TABLE enf_review_record DROP CONSTRAINT ck_enf_review_scope;
ALTER TABLE enf_review_record ADD CONSTRAINT ck_enf_review_scope CHECK (scope_code IN ('SYNTHETIC_PROCESS_TEST'));
ALTER TABLE enf_work_item DROP CONSTRAINT ck_enf_work_scope;
ALTER TABLE enf_work_item ADD CONSTRAINT ck_enf_work_scope CHECK (scope_code IN ('SYNTHETIC_V0'));
