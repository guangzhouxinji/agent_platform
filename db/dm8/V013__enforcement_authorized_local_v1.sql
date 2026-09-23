CREATE TABLE enf_local_authorization (
    batch_id VARCHAR(96) NOT NULL,
    approval_decision_id VARCHAR(160) NOT NULL,
    approved_by VARCHAR(160) NOT NULL,
    approved_at TIMESTAMP NOT NULL,
    source_name VARCHAR(240) NOT NULL,
    source_type VARCHAR(80) NOT NULL,
    allowed_use VARCHAR(1000) NOT NULL,
    record_scope VARCHAR(1000) NOT NULL,
    retention_until TIMESTAMP NOT NULL,
    withdrawal_contact VARCHAR(240) NOT NULL,
    withdrawal_process VARCHAR(1000) NOT NULL,
    manifest_json CLOB NOT NULL,
    manifest_sha256 VARCHAR(64) NOT NULL,
    package_sha256 VARCHAR(64) NOT NULL,
    CONSTRAINT pk_enf_local_authorization PRIMARY KEY (batch_id),
    CONSTRAINT fk_enf_local_authorization_batch FOREIGN KEY (batch_id) REFERENCES enf_local_source_batch (batch_id)
);

CREATE TABLE enf_local_rule_release (
    rule_id VARCHAR(160) NOT NULL,
    rule_version VARCHAR(160) NOT NULL,
    batch_id VARCHAR(96) NOT NULL,
    release_status VARCHAR(30) NOT NULL,
    approved_by VARCHAR(160) NOT NULL,
    approved_at TIMESTAMP NOT NULL,
    effective_from TIMESTAMP NOT NULL,
    applicability VARCHAR(1000) NOT NULL,
    rule_type VARCHAR(80) NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    withdrawn_at TIMESTAMP NULL,
    withdrawal_reason VARCHAR(1000) NULL,
    CONSTRAINT pk_enf_local_rule_release PRIMARY KEY (rule_id, rule_version),
    CONSTRAINT fk_enf_local_rule_release_rule FOREIGN KEY (rule_id, rule_version)
        REFERENCES enf_local_rule_snapshot (rule_id, rule_version),
    CONSTRAINT fk_enf_local_rule_release_batch FOREIGN KEY (batch_id) REFERENCES enf_local_source_batch (batch_id),
    CONSTRAINT ck_enf_local_rule_release_status CHECK (release_status IN ('PUBLISHED','WITHDRAWN'))
);

ALTER TABLE enf_work_item DROP CONSTRAINT ck_enf_work_scope;
ALTER TABLE enf_work_item ADD CONSTRAINT ck_enf_work_scope CHECK (scope_code IN ('SYNTHETIC_V0','AUTHORIZED_LOCAL'));
ALTER TABLE enf_review_record DROP CONSTRAINT ck_enf_review_scope;
ALTER TABLE enf_review_record ADD CONSTRAINT ck_enf_review_scope CHECK (scope_code IN ('SYNTHETIC_PROCESS_TEST','AUTHORIZED_LOCAL_REVIEW'));

CREATE INDEX ix_enf_local_rule_release_status ON enf_local_rule_release (release_status, effective_from);
