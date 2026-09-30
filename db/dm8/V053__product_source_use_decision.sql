CREATE TABLE con4_source_product_decision (
    decision_id VARCHAR(36) NOT NULL PRIMARY KEY,
    decision_version VARCHAR(100) NOT NULL,
    decision_ref VARCHAR(500) NOT NULL,
    decision_sha VARCHAR(64) NOT NULL,
    rights_evidence_status VARCHAR(16) NOT NULL,
    organization_id VARCHAR(200) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    scope_id VARCHAR(36) NOT NULL,
    retention_ref VARCHAR(200) NOT NULL,
    retention_version BIGINT NOT NULL,
    retention_evidence_sha VARCHAR(64) NOT NULL,
    CONSTRAINT fk_con4_product_decision_admission FOREIGN KEY (decision_id)
        REFERENCES con4_source_admission(decision_id),
    CONSTRAINT ck_con4_rights_evidence_status CHECK
        (rights_evidence_status IN ('UNRESOLVED','VERIFIED','WITHDRAWN')),
    CONSTRAINT ck_con4_product_retention_version CHECK (retention_version > 0)
);
