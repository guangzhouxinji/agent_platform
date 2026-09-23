CREATE TABLE adj_local_source_batch (
    batch_id VARCHAR(96) NOT NULL, dataset_id VARCHAR(120) NOT NULL, dataset_version VARCHAR(120) NOT NULL,
    data_mode VARCHAR(40) NOT NULL, record_count INT NOT NULL, records_sha256 VARCHAR(64) NOT NULL,
    authorization_label VARCHAR(120) NOT NULL, external_model_allowed CHAR(1) NOT NULL, import_status VARCHAR(30) NOT NULL,
    imported_at TIMESTAMP NOT NULL, CONSTRAINT pk_adj_local_batch PRIMARY KEY (batch_id),
    CONSTRAINT uq_adj_local_dataset UNIQUE (dataset_id, dataset_version),
    CONSTRAINT ck_adj_local_mode CHECK (data_mode IN ('SYNTHETIC_LOCAL','PUBLIC_LOCAL','AUTHORIZED_LOCAL')),
    CONSTRAINT ck_adj_local_model CHECK (external_model_allowed IN ('Y','N')),
    CONSTRAINT ck_adj_local_status CHECK (import_status IN ('COMPLETED','FAILED')),
    CONSTRAINT ck_adj_local_count CHECK (record_count >= 0)
);
CREATE TABLE adj_local_case (
    source_id VARCHAR(160) NOT NULL, source_version VARCHAR(160) NOT NULL, batch_id VARCHAR(96) NOT NULL,
    source_system VARCHAR(160) NOT NULL, source_location VARCHAR(1000) NOT NULL, availability VARCHAR(40) NOT NULL,
    payload_json CLOB NOT NULL, payload_sha256 VARCHAR(64) NOT NULL, withdrawn_at TIMESTAMP, withdrawal_reason VARCHAR(1000),
    CONSTRAINT pk_adj_local_case PRIMARY KEY (source_id, source_version),
    CONSTRAINT fk_adj_local_case_batch FOREIGN KEY (batch_id) REFERENCES adj_local_source_batch (batch_id),
    CONSTRAINT ck_adj_local_case_availability CHECK (availability IN ('AVAILABLE','SOURCE_UNAVAILABLE','WITHDRAWN'))
);
CREATE TABLE adj_local_material (
    source_id VARCHAR(160) NOT NULL, source_version VARCHAR(160) NOT NULL, batch_id VARCHAR(96) NOT NULL, case_id VARCHAR(160) NOT NULL,
    case_version VARCHAR(160) NOT NULL, source_system VARCHAR(160) NOT NULL, source_location VARCHAR(1000) NOT NULL,
    availability VARCHAR(40) NOT NULL, payload_json CLOB NOT NULL, payload_sha256 VARCHAR(64) NOT NULL, withdrawn_at TIMESTAMP, withdrawal_reason VARCHAR(1000),
    CONSTRAINT pk_adj_local_material PRIMARY KEY (source_id, source_version),
    CONSTRAINT fk_adj_local_material_batch FOREIGN KEY (batch_id) REFERENCES adj_local_source_batch (batch_id),
    CONSTRAINT ck_adj_local_material_availability CHECK (availability IN ('AVAILABLE','SOURCE_UNAVAILABLE','WITHDRAWN'))
);
CREATE TABLE adj_local_legal_snapshot (
    source_id VARCHAR(160) NOT NULL, source_version VARCHAR(160) NOT NULL, batch_id VARCHAR(96) NOT NULL, legal_code VARCHAR(160) NOT NULL,
    source_system VARCHAR(160) NOT NULL, source_location VARCHAR(1000) NOT NULL, availability VARCHAR(40) NOT NULL,
    payload_json CLOB NOT NULL, payload_sha256 VARCHAR(64) NOT NULL, withdrawn_at TIMESTAMP, withdrawal_reason VARCHAR(1000),
    CONSTRAINT pk_adj_local_legal PRIMARY KEY (source_id, source_version),
    CONSTRAINT fk_adj_local_legal_batch FOREIGN KEY (batch_id) REFERENCES adj_local_source_batch (batch_id),
    CONSTRAINT ck_adj_local_legal_availability CHECK (availability IN ('AVAILABLE','SOURCE_UNAVAILABLE','WITHDRAWN'))
);
CREATE TABLE adj_local_template_snapshot (
    source_id VARCHAR(160) NOT NULL, source_version VARCHAR(160) NOT NULL, batch_id VARCHAR(96) NOT NULL,
    source_system VARCHAR(160) NOT NULL, source_location VARCHAR(1000) NOT NULL, availability VARCHAR(40) NOT NULL,
    payload_json CLOB NOT NULL, payload_sha256 VARCHAR(64) NOT NULL, withdrawn_at TIMESTAMP, withdrawal_reason VARCHAR(1000),
    CONSTRAINT pk_adj_local_template PRIMARY KEY (source_id, source_version),
    CONSTRAINT fk_adj_local_template_batch FOREIGN KEY (batch_id) REFERENCES adj_local_source_batch (batch_id),
    CONSTRAINT ck_adj_local_template_availability CHECK (availability IN ('AVAILABLE','SOURCE_UNAVAILABLE','WITHDRAWN'))
);
CREATE INDEX ix_adj_local_case_batch ON adj_local_case (batch_id, source_id);
CREATE INDEX ix_adj_local_material_case ON adj_local_material (case_id, case_version, source_id);
