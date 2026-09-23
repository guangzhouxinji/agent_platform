CREATE TABLE mock_center_catalog_import (
    batch_id VARCHAR(64) NOT NULL,
    dataset_version VARCHAR(100) NOT NULL,
    dataset_digest VARCHAR(64) NOT NULL,
    record_count INT NOT NULL,
    imported_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_mock_center_catalog_import PRIMARY KEY (batch_id),
    CONSTRAINT uq_mock_center_catalog_dataset UNIQUE (dataset_version, dataset_digest),
    CONSTRAINT ck_mock_center_catalog_import_count CHECK (record_count >= 0)
);

CREATE TABLE mock_center_patent_record (
    record_id VARCHAR(64) NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    source_record_id VARCHAR(200) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    publication_number VARCHAR(200) NOT NULL,
    application_number VARCHAR(200),
    title CLOB NOT NULL,
    applicants_json CLOB NOT NULL,
    inventors_json CLOB NOT NULL,
    application_date DATE,
    publication_date DATE,
    ipc_json CLOB NOT NULL,
    abstract_text CLOB,
    abstract_state VARCHAR(30) NOT NULL,
    source_url VARCHAR(2000) NOT NULL,
    collected_at VARCHAR(100) NOT NULL,
    content_digest VARCHAR(64) NOT NULL,
    catalog_version VARCHAR(100) NOT NULL,
    import_batch_id VARCHAR(64) NOT NULL,
    data_mode VARCHAR(30) NOT NULL,
    authority_status VARCHAR(40) NOT NULL,
    CONSTRAINT pk_mock_center_patent_record PRIMARY KEY (record_id),
    CONSTRAINT uq_mock_center_patent_source UNIQUE (source_id, source_record_id, source_version),
    CONSTRAINT fk_mock_center_patent_import FOREIGN KEY (import_batch_id) REFERENCES mock_center_catalog_import (batch_id),
    CONSTRAINT ck_mock_center_patent_data_mode CHECK (data_mode = 'PUBLIC_PILOT'),
    CONSTRAINT ck_mock_center_patent_authority CHECK (authority_status = 'NON_CENTER_PUBLIC_SOURCE')
);

CREATE INDEX ix_mock_center_patent_publication ON mock_center_patent_record (publication_number);
CREATE INDEX ix_mock_center_patent_published ON mock_center_patent_record (publication_date, source_record_id);
