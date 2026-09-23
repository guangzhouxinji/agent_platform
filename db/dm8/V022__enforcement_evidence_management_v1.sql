CREATE TABLE enf_evidence_record (
    evidence_id VARCHAR(64) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    evidence_name VARCHAR(500) NOT NULL,
    evidence_type VARCHAR(64) NOT NULL,
    evidence_status VARCHAR(32) NOT NULL,
    related_lead_id VARCHAR(64) NOT NULL,
    related_case_ref VARCHAR(128),
    source_channel VARCHAR(32) NOT NULL,
    collector_ref VARCHAR(256),
    collection_time TIMESTAMP,
    collection_missing_reason VARCHAR(1000),
    physical_description CLOB,
    custody_information CLOB,
    record_version INTEGER NOT NULL,
    created_by VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(256) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_evidence_record PRIMARY KEY (evidence_id),
    CONSTRAINT ck_enf_evidence_status CHECK (evidence_status IN ('PENDING_VERIFICATION','VERIFIED','EXCLUDED','ARCHIVED')),
    CONSTRAINT ck_enf_evidence_source CHECK (source_channel IN ('AUTHORIZED_UPLOAD','AUTHORIZED_LOCAL')),
    CONSTRAINT ck_enf_evidence_record_collection CHECK ((collector_ref IS NOT NULL AND collection_time IS NOT NULL) OR collection_missing_reason IS NOT NULL)
);
CREATE INDEX ix_enf_evidence_record_list ON enf_evidence_record(organization_ref, related_lead_id, evidence_status, updated_at);

CREATE TABLE enf_evidence_content_version (
    content_version_id VARCHAR(64) NOT NULL,
    evidence_id VARCHAR(64) NOT NULL,
    content_version INTEGER NOT NULL,
    original_content_version INTEGER,
    derived_kind VARCHAR(32) NOT NULL,
    controlled_resource_ref VARCHAR(256) NOT NULL,
    file_name VARCHAR(500),
    media_type VARCHAR(128),
    content_size BIGINT,
    digest_algorithm VARCHAR(16),
    content_sha256 VARCHAR(64),
    content_bytes BLOB,
    previewable SMALLINT NOT NULL,
    created_by VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_evidence_content PRIMARY KEY (content_version_id),
    CONSTRAINT uq_enf_evidence_content UNIQUE (evidence_id, content_version),
    CONSTRAINT ck_enf_evidence_derived CHECK (derived_kind IN ('ORIGINAL','OCR_TEXT','TRANSCODE','PREVIEW','OTHER')),
    CONSTRAINT ck_enf_evidence_origin CHECK ((derived_kind='ORIGINAL' AND original_content_version IS NULL) OR (derived_kind<>'ORIGINAL' AND original_content_version IS NOT NULL)),
    CONSTRAINT ck_enf_evidence_bytes CHECK ((content_bytes IS NOT NULL AND content_size IS NOT NULL AND digest_algorithm='SHA-256' AND content_sha256 IS NOT NULL) OR (content_bytes IS NULL AND content_size IS NULL AND digest_algorithm IS NULL AND content_sha256 IS NULL)),
    CONSTRAINT ck_enf_evidence_preview CHECK (previewable IN (0,1))
);
CREATE INDEX ix_enf_evidence_content_evidence ON enf_evidence_content_version(evidence_id, content_version);
CREATE INDEX ix_enf_evidence_content_digest ON enf_evidence_content_version(content_sha256);

CREATE TABLE enf_evidence_metadata_history (
    history_id VARCHAR(64) NOT NULL,
    evidence_id VARCHAR(64) NOT NULL,
    record_version INTEGER NOT NULL,
    evidence_name VARCHAR(500) NOT NULL,
    evidence_type VARCHAR(64) NOT NULL,
    collector_ref VARCHAR(256),
    collection_time TIMESTAMP,
    collection_missing_reason VARCHAR(1000),
    physical_description CLOB,
    custody_information CLOB,
    change_reason VARCHAR(1000) NOT NULL,
    changed_by VARCHAR(256) NOT NULL,
    changed_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_evidence_metadata_history PRIMARY KEY (history_id),
    CONSTRAINT uq_enf_evidence_metadata_version UNIQUE (evidence_id, record_version)
);

CREATE TABLE enf_evidence_status_event (
    event_id VARCHAR(64) NOT NULL,
    evidence_id VARCHAR(64) NOT NULL,
    from_status VARCHAR(32) NOT NULL,
    to_status VARCHAR(32) NOT NULL,
    reason_text VARCHAR(1000) NOT NULL,
    actor_ref VARCHAR(256) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_evidence_status_event PRIMARY KEY (event_id),
    CONSTRAINT ck_enf_evidence_status_to CHECK (to_status IN ('VERIFIED','EXCLUDED','ARCHIVED'))
);

CREATE TABLE enf_evidence_access_log (
    access_id VARCHAR(64) NOT NULL,
    evidence_id VARCHAR(64) NOT NULL,
    content_version INTEGER NOT NULL,
    action_type VARCHAR(16) NOT NULL,
    actor_ref VARCHAR(256) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_evidence_access_log PRIMARY KEY (access_id),
    CONSTRAINT ck_enf_evidence_access_action CHECK (action_type IN ('PREVIEW','DOWNLOAD'))
);
CREATE INDEX ix_enf_evidence_access_record ON enf_evidence_access_log(evidence_id, occurred_at);

CREATE TABLE enf_evidence_audit (
    audit_id VARCHAR(64) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    evidence_id VARCHAR(64) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(256) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    detail_text VARCHAR(2000) NOT NULL,
    CONSTRAINT pk_enf_evidence_audit PRIMARY KEY (audit_id)
);
CREATE INDEX ix_enf_evidence_audit_record ON enf_evidence_audit(evidence_id, occurred_at);
