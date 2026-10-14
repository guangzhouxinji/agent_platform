ALTER TABLE con4_result_set DROP CONSTRAINT ck_con4_result_digest_version;
ALTER TABLE con4_result_set ADD CONSTRAINT ck_con4_result_digest_version
    CHECK (digest_version IN ('RESULT_DIGEST_PRE_STORAGE/v1','RESULT_DIGEST_DECIMAL_12/v2',
        'RESULT_DIGEST_FIELDS_DECIMAL_12/v3'));
ALTER TABLE con4_result_set ADD CONSTRAINT uq_con4_result_field_root
    UNIQUE (result_set_id, attempt_id, digest_version);

CREATE TABLE con4_search_field_scope (
    attempt_id VARCHAR(36) NOT NULL PRIMARY KEY,
    result_set_id VARCHAR(36) NOT NULL UNIQUE,
    digest_version VARCHAR(100) DEFAULT 'RESULT_DIGEST_FIELDS_DECIMAL_12/v3' NOT NULL,
    scope_version VARCHAR(100) DEFAULT 'SEARCH_FIELD_SCOPE/v2' NOT NULL,
    text_fields_json VARCHAR(400) NOT NULL,
    models_json CLOB NOT NULL,
    scope_sha CHAR(64) NOT NULL,
    CONSTRAINT fk_con4_field_scope_attempt FOREIGN KEY (attempt_id)
        REFERENCES con4_search_attempt(attempt_id),
    CONSTRAINT fk_con4_field_scope_result FOREIGN KEY (result_set_id, attempt_id, digest_version)
        REFERENCES con4_result_set(result_set_id, attempt_id, digest_version),
    CONSTRAINT ck_con4_field_scope_digest CHECK (digest_version='RESULT_DIGEST_FIELDS_DECIMAL_12/v3'),
    CONSTRAINT ck_con4_field_scope_version CHECK (scope_version='SEARCH_FIELD_SCOPE/v2')
);

CREATE TABLE con4_result_field_evidence (
    result_set_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(36) NOT NULL,
    evidence_version VARCHAR(100) DEFAULT 'FIELD_RESULT_EVIDENCE/v1' NOT NULL,
    fields_json CLOB NOT NULL,
    evidence_sha CHAR(64) NOT NULL,
    PRIMARY KEY (result_set_id, member_id),
    CONSTRAINT fk_con4_field_evidence_scope FOREIGN KEY (result_set_id)
        REFERENCES con4_search_field_scope(result_set_id),
    CONSTRAINT fk_con4_field_evidence_member FOREIGN KEY (result_set_id, member_id)
        REFERENCES con4_result_member(result_set_id, member_id),
    CONSTRAINT ck_con4_field_evidence_version CHECK (evidence_version='FIELD_RESULT_EVIDENCE/v1')
);
