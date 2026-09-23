CREATE TABLE enf_local_source_batch (
    batch_id VARCHAR(96) NOT NULL,
    dataset_id VARCHAR(120) NOT NULL,
    dataset_version VARCHAR(120) NOT NULL,
    data_mode VARCHAR(40) NOT NULL,
    record_count INT NOT NULL,
    records_sha256 VARCHAR(64) NOT NULL,
    authorization_label VARCHAR(120) NOT NULL,
    external_model_allowed CHAR(1) NOT NULL,
    import_status VARCHAR(30) NOT NULL,
    imported_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_local_batch PRIMARY KEY (batch_id),
    CONSTRAINT uq_enf_local_dataset UNIQUE (dataset_id, dataset_version),
    CONSTRAINT ck_enf_local_mode CHECK (data_mode IN ('SYNTHETIC_LOCAL','PUBLIC_LOCAL','AUTHORIZED_LOCAL')),
    CONSTRAINT ck_enf_local_model CHECK (external_model_allowed IN ('Y','N')),
    CONSTRAINT ck_enf_local_status CHECK (import_status IN ('COMPLETED','FAILED')),
    CONSTRAINT ck_enf_local_count CHECK (record_count >= 0)
);

CREATE TABLE enf_local_clue (
    source_id VARCHAR(160) NOT NULL,
    source_version VARCHAR(160) NOT NULL,
    batch_id VARCHAR(96) NOT NULL,
    source_system VARCHAR(160) NOT NULL,
    source_location VARCHAR(1000) NOT NULL,
    availability VARCHAR(40) NOT NULL,
    payload_json CLOB NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    CONSTRAINT pk_enf_local_clue PRIMARY KEY (source_id, source_version),
    CONSTRAINT fk_enf_local_clue_batch FOREIGN KEY (batch_id) REFERENCES enf_local_source_batch (batch_id),
    CONSTRAINT ck_enf_local_clue_availability CHECK (availability IN ('AVAILABLE','SOURCE_UNAVAILABLE','WITHDRAWN'))
);

CREATE TABLE enf_local_evidence (
    source_id VARCHAR(160) NOT NULL,
    source_version VARCHAR(160) NOT NULL,
    batch_id VARCHAR(96) NOT NULL,
    related_clue_id VARCHAR(160) NOT NULL,
    source_system VARCHAR(160) NOT NULL,
    source_location VARCHAR(1000) NOT NULL,
    availability VARCHAR(40) NOT NULL,
    controlled_resource_ref VARCHAR(1000) NOT NULL,
    payload_json CLOB NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    CONSTRAINT pk_enf_local_evidence PRIMARY KEY (source_id, source_version),
    CONSTRAINT fk_enf_local_evidence_batch FOREIGN KEY (batch_id) REFERENCES enf_local_source_batch (batch_id),
    CONSTRAINT ck_enf_local_evidence_availability CHECK (availability IN ('AVAILABLE','SOURCE_UNAVAILABLE','UNSUPPORTED_CONTENT','WITHDRAWN'))
);

CREATE TABLE enf_local_rule_snapshot (
    rule_id VARCHAR(160) NOT NULL,
    rule_version VARCHAR(160) NOT NULL,
    batch_id VARCHAR(96) NOT NULL,
    source_system VARCHAR(160) NOT NULL,
    source_location VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_enf_local_rule PRIMARY KEY (rule_id, rule_version),
    CONSTRAINT fk_enf_local_rule_batch FOREIGN KEY (batch_id) REFERENCES enf_local_source_batch (batch_id)
);

CREATE TABLE enf_local_right_snapshot (
    right_id VARCHAR(160) NOT NULL,
    clue_id VARCHAR(160) NOT NULL,
    right_version VARCHAR(160) NOT NULL,
    batch_id VARCHAR(96) NOT NULL,
    source_system VARCHAR(160) NOT NULL,
    source_location VARCHAR(1000) NOT NULL,
    availability VARCHAR(40) NOT NULL,
    CONSTRAINT pk_enf_local_right PRIMARY KEY (right_id, right_version),
    CONSTRAINT fk_enf_local_right_batch FOREIGN KEY (batch_id) REFERENCES enf_local_source_batch (batch_id),
    CONSTRAINT ck_enf_local_right_availability CHECK (availability IN ('AVAILABLE','SOURCE_UNAVAILABLE','WITHDRAWN'))
);

CREATE INDEX ix_enf_local_clue_batch ON enf_local_clue (batch_id, source_id);
CREATE INDEX ix_enf_local_evidence_clue ON enf_local_evidence (related_clue_id, source_id);
CREATE INDEX ix_enf_local_right_clue ON enf_local_right_snapshot (clue_id, availability);
