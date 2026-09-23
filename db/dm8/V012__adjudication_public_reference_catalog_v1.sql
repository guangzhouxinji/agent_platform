CREATE TABLE adj_public_source_registration (
    source_id VARCHAR(160) NOT NULL,
    source_name VARCHAR(300) NOT NULL,
    source_url VARCHAR(1000) NOT NULL,
    access_mode VARCHAR(40) NOT NULL,
    site_restrictions VARCHAR(2000) NOT NULL,
    owner_ref VARCHAR(300) NOT NULL,
    allowed_uses VARCHAR(1000) NOT NULL,
    allowed_categories VARCHAR(300) NOT NULL,
    forbidden_uses VARCHAR(1000) NOT NULL,
    external_model_allowed CHAR(1) NOT NULL,
    review_status VARCHAR(40) NOT NULL,
    reviewed_at TIMESTAMP,
    approval_id VARCHAR(160) NOT NULL,
    approval_sha256 VARCHAR(64) NOT NULL,
    retention_period VARCHAR(120) NOT NULL,
    attribution VARCHAR(1000) NOT NULL,
    revoked_at TIMESTAMP,
    revocation_reason VARCHAR(1000),
    CONSTRAINT pk_adj_public_source_registration PRIMARY KEY (source_id),
    CONSTRAINT ck_adj_public_source_model CHECK (external_model_allowed IN ('Y','N')),
    CONSTRAINT ck_adj_public_source_review CHECK (review_status IN ('REVIEW_REQUIRED','APPROVED','REVOKED'))
);

CREATE TABLE adj_public_reference_batch (
    batch_id VARCHAR(96) NOT NULL,
    source_id VARCHAR(160) NOT NULL,
    collector_version VARCHAR(120) NOT NULL,
    request_summary VARCHAR(2000) NOT NULL,
    raw_response_sha256 VARCHAR(64) NOT NULL,
    raw_response_locator VARCHAR(1000) NOT NULL,
    record_count INT NOT NULL,
    records_sha256 VARCHAR(64) NOT NULL,
    imported_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_adj_public_reference_batch PRIMARY KEY (batch_id),
    CONSTRAINT fk_adj_public_reference_batch_source FOREIGN KEY (source_id) REFERENCES adj_public_source_registration (source_id),
    CONSTRAINT ck_adj_public_reference_batch_count CHECK (record_count >= 0)
);

CREATE TABLE adj_public_reference_snapshot (
    reference_id VARCHAR(160) NOT NULL,
    source_version VARCHAR(160) NOT NULL,
    batch_id VARCHAR(96) NOT NULL,
    category VARCHAR(48) NOT NULL,
    availability VARCHAR(40) NOT NULL,
    payload_json CLOB NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    raw_response_sha256 VARCHAR(64) NOT NULL,
    raw_response_locator VARCHAR(1000) NOT NULL,
    withdrawn_at TIMESTAMP,
    withdrawal_reason VARCHAR(1000),
    CONSTRAINT pk_adj_public_reference_snapshot PRIMARY KEY (reference_id, source_version),
    CONSTRAINT fk_adj_public_reference_snapshot_batch FOREIGN KEY (batch_id) REFERENCES adj_public_reference_batch (batch_id),
    CONSTRAINT ck_adj_public_reference_category CHECK (category IN ('PUBLIC_LEGAL_REFERENCE','PUBLIC_JUDGMENT_REFERENCE','AUTHORIZED_TEMPLATE_REFERENCE')),
    CONSTRAINT ck_adj_public_reference_availability CHECK (availability IN ('AVAILABLE','SOURCE_UNAVAILABLE','WITHDRAWN'))
);

CREATE INDEX ix_adj_public_reference_batch ON adj_public_reference_snapshot (batch_id, reference_id);
