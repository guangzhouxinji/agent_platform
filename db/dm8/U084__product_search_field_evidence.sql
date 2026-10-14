-- Run only under the final-product stopped-write migration protocol.
-- Refuse before any destructive DDL if v3 evidence exists; do not convert it to legacy TITLE.
ALTER TABLE con4_result_set ADD CONSTRAINT ck_con4_field_rollback_guard
    CHECK (digest_version<>'RESULT_DIGEST_FIELDS_DECIMAL_12/v3');
DROP TABLE con4_result_field_evidence;
DROP TABLE con4_search_field_scope;
ALTER TABLE con4_result_set DROP CONSTRAINT uq_con4_result_field_root;
ALTER TABLE con4_result_set DROP CONSTRAINT ck_con4_result_digest_version;
ALTER TABLE con4_result_set ADD CONSTRAINT ck_con4_result_digest_version
    CHECK (digest_version IN ('RESULT_DIGEST_PRE_STORAGE/v1','RESULT_DIGEST_DECIMAL_12/v2'));
ALTER TABLE con4_result_set DROP CONSTRAINT ck_con4_field_rollback_guard;
