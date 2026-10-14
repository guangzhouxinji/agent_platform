ALTER TABLE kb_trademark MODIFY mark_name NULL;
ALTER TABLE kb_record_version DROP CONSTRAINT ck_kb_version_mode;
ALTER TABLE kb_record_version ADD CONSTRAINT ck_kb_version_mode
    CHECK (data_mode IN ('PUBLIC_LOCAL','PRODUCT_SOURCE_LOCAL'));

CREATE TABLE con1tm_capture_request (
    receipt_id VARCHAR(96) NOT NULL, organization_id VARCHAR(160) NOT NULL,
    source_id VARCHAR(80) NOT NULL, contract_version VARCHAR(300) NOT NULL,
    idempotency_key VARCHAR(300) NOT NULL, request_sha VARCHAR(64) NOT NULL,
    request_payload CLOB NOT NULL, task_id VARCHAR(160) NOT NULL,
    authorization_version BIGINT NOT NULL, maintainer_ref VARCHAR(300) NOT NULL,
    certificate_sha VARCHAR(64) NOT NULL, decision_id VARCHAR(160) NOT NULL,
    source_epoch BIGINT NOT NULL, source_row_version BIGINT NOT NULL,
    registered_source_version VARCHAR(300) NOT NULL,
    manifest_ref VARCHAR(500) NOT NULL, manifest_sha VARCHAR(64) NOT NULL,
    records_sha VARCHAR(64) NOT NULL, inventory_sha VARCHAR(64) NOT NULL,
    binding_set_sha VARCHAR(64) NOT NULL, map_id VARCHAR(160) NOT NULL,
    map_version VARCHAR(120) NOT NULL, map_sha VARCHAR(64) NOT NULL,
    retention_ref VARCHAR(300) NOT NULL, retention_version BIGINT NOT NULL,
    retention_evidence_sha VARCHAR(64) NOT NULL, approved_scope_sha VARCHAR(64) NOT NULL,
    approval_sha VARCHAR(64) NOT NULL, expires_at TIMESTAMP NOT NULL,
    record_count INT NOT NULL, status VARCHAR(20) NOT NULL, committed_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con1tm_request PRIMARY KEY (receipt_id),
    CONSTRAINT uq_con1tm_request UNIQUE (organization_id,source_id,contract_version,idempotency_key),
    CONSTRAINT fk_con1tm_request_source FOREIGN KEY (source_id) REFERENCES kb_source(source_id),
    CONSTRAINT ck_con1tm_request_status CHECK (status='COMMITTED'),
    CONSTRAINT ck_con1tm_request_count CHECK (record_count>=0 AND record_count<=1000),
    CONSTRAINT ck_con1tm_request_until CHECK (expires_at>committed_at)
);

CREATE TABLE con1tm_record_binding (
    source_id VARCHAR(80) NOT NULL, contract_version VARCHAR(300) NOT NULL,
    source_record_id VARCHAR(240) NOT NULL, record_version VARCHAR(300) NOT NULL,
    registered_source_version VARCHAR(300) NOT NULL, record_version_id VARCHAR(96) NOT NULL,
    raw_ref VARCHAR(500) NOT NULL, raw_sha VARCHAR(64) NOT NULL, raw_bytes BIGINT NOT NULL,
    manifest_sha VARCHAR(64) NOT NULL, normalized_sha VARCHAR(64) NOT NULL,
    relation_sha VARCHAR(64) NOT NULL, map_id VARCHAR(160) NOT NULL,
    map_version VARCHAR(120) NOT NULL, map_sha VARCHAR(64) NOT NULL,
    source_nature VARCHAR(40) NOT NULL, nature_evidence_ref VARCHAR(500) NOT NULL,
    nature_evidence_sha VARCHAR(64) NOT NULL, capture_request_id VARCHAR(96) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con1tm_binding PRIMARY KEY (source_id,contract_version,source_record_id,record_version),
    CONSTRAINT fk_con1tm_binding_version FOREIGN KEY (record_version_id) REFERENCES kb_record_version(record_version_id),
    CONSTRAINT fk_con1tm_binding_request FOREIGN KEY (capture_request_id) REFERENCES con1tm_capture_request(receipt_id),
    CONSTRAINT ck_con1tm_binding_nature CHECK (source_nature IN ('PUBLIC','RESTRICTED','APPROVED_SUBSTITUTE')),
    CONSTRAINT ck_con1tm_binding_bytes CHECK (raw_bytes>0)
);

CREATE TABLE kb_trademark_party (
    record_version_id VARCHAR(96) NOT NULL, party_order INT NOT NULL,
    party_name VARCHAR(1000) NOT NULL, address_text VARCHAR(2000),
    CONSTRAINT pk_kb_trademark_party PRIMARY KEY(record_version_id,party_order),
    CONSTRAINT fk_kb_trademark_party FOREIGN KEY(record_version_id) REFERENCES kb_trademark(record_version_id),
    CONSTRAINT ck_kb_trademark_party_order CHECK(party_order>=0)
);
CREATE INDEX ix_con1tm_binding_capture ON con1tm_record_binding(capture_request_id);
CREATE INDEX ix_con1tm_binding_version ON con1tm_record_binding(record_version_id);
CREATE INDEX ix_kb_trademark_party_name ON kb_trademark_party(party_name);
