CREATE TABLE kb_source (
    source_id VARCHAR(80) NOT NULL, source_name VARCHAR(300) NOT NULL, provider_name VARCHAR(300),
    entry_url VARCHAR(1000) NOT NULL, jurisdiction VARCHAR(40), access_mode VARCHAR(80) NOT NULL,
    authority_status VARCHAR(100) NOT NULL, license_review_status VARCHAR(40) NOT NULL,
    usage_decision VARCHAR(80) NOT NULL, limitation_text CLOB, created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_kb_source PRIMARY KEY (source_id)
);

CREATE TABLE kb_ingest_batch (
    batch_id VARCHAR(80) NOT NULL, dataset_id VARCHAR(120) NOT NULL, source_id VARCHAR(80) NOT NULL,
    collector_version VARCHAR(100) NOT NULL, started_at TIMESTAMP NOT NULL, completed_at TIMESTAMP,
    query_scope CLOB, record_count INT NOT NULL, records_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL, limitation_text CLOB,
    CONSTRAINT pk_kb_batch PRIMARY KEY (batch_id),
    CONSTRAINT uq_kb_batch_dataset UNIQUE (dataset_id, records_sha256),
    CONSTRAINT fk_kb_batch_source FOREIGN KEY (source_id) REFERENCES kb_source (source_id),
    CONSTRAINT ck_kb_batch_status CHECK (status IN ('IMPORTING','COMPLETED','FAILED')),
    CONSTRAINT ck_kb_batch_count CHECK (record_count >= 0)
);

CREATE TABLE kb_raw_record (
    raw_record_id VARCHAR(96) NOT NULL, batch_id VARCHAR(80) NOT NULL, source_record_id VARCHAR(240) NOT NULL,
    request_url VARCHAR(1000), content_type VARCHAR(120) NOT NULL, content_path VARCHAR(1000),
    raw_content CLOB, content_sha256 VARCHAR(64) NOT NULL, captured_at TIMESTAMP,
    response_metadata CLOB,
    CONSTRAINT pk_kb_raw_record PRIMARY KEY (raw_record_id),
    CONSTRAINT uq_kb_raw_record UNIQUE (batch_id, source_record_id),
    CONSTRAINT fk_kb_raw_batch FOREIGN KEY (batch_id) REFERENCES kb_ingest_batch (batch_id)
);

CREATE TABLE kb_record_version (
    record_version_id VARCHAR(96) NOT NULL, domain_code VARCHAR(30) NOT NULL, stable_record_key VARCHAR(240) NOT NULL,
    source_id VARCHAR(80) NOT NULL, source_version VARCHAR(300) NOT NULL, raw_record_id VARCHAR(96) NOT NULL,
    normalized_payload CLOB NOT NULL, normalized_sha256 VARCHAR(64) NOT NULL, data_mode VARCHAR(40) NOT NULL,
    effective_at TIMESTAMP, imported_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_kb_record_version PRIMARY KEY (record_version_id),
    CONSTRAINT uq_kb_record_source_version UNIQUE (domain_code, source_id, stable_record_key, source_version),
    CONSTRAINT fk_kb_version_source FOREIGN KEY (source_id) REFERENCES kb_source (source_id),
    CONSTRAINT fk_kb_version_raw FOREIGN KEY (raw_record_id) REFERENCES kb_raw_record (raw_record_id),
    CONSTRAINT ck_kb_version_domain CHECK (domain_code IN ('PATENT','TRADEMARK','CASE','REGULATION')),
    CONSTRAINT ck_kb_version_mode CHECK (data_mode IN ('PUBLIC_LOCAL'))
);

CREATE TABLE kb_field_provenance (
    provenance_id VARCHAR(96) NOT NULL, record_version_id VARCHAR(96) NOT NULL, field_path VARCHAR(300) NOT NULL,
    value_state VARCHAR(40) NOT NULL, value_sha256 VARCHAR(64), source_locator VARCHAR(1000),
    extraction_method VARCHAR(80) NOT NULL, confidence_value DECIMAL(6,5), review_status VARCHAR(40) NOT NULL,
    derived_flag CHAR(1) NOT NULL, method_version VARCHAR(120),
    CONSTRAINT pk_kb_provenance PRIMARY KEY (provenance_id),
    CONSTRAINT uq_kb_provenance_field UNIQUE (record_version_id, field_path),
    CONSTRAINT fk_kb_provenance_version FOREIGN KEY (record_version_id) REFERENCES kb_record_version (record_version_id),
    CONSTRAINT ck_kb_provenance_state CHECK (value_state IN ('PRESENT','MISSING','SOURCE_NOT_PROVIDED','PARSE_FAILED','NOT_APPLICABLE')),
    CONSTRAINT ck_kb_provenance_derived CHECK (derived_flag IN ('Y','N'))
);

CREATE TABLE kb_ingest_error (
    error_id VARCHAR(96) NOT NULL, batch_id VARCHAR(80) NOT NULL, source_record_id VARCHAR(240),
    stage_code VARCHAR(60) NOT NULL, error_code VARCHAR(100) NOT NULL, retryable CHAR(1) NOT NULL,
    error_summary CLOB NOT NULL, occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_kb_ingest_error PRIMARY KEY (error_id),
    CONSTRAINT fk_kb_error_batch FOREIGN KEY (batch_id) REFERENCES kb_ingest_batch (batch_id),
    CONSTRAINT ck_kb_error_retry CHECK (retryable IN ('Y','N'))
);

CREATE TABLE kb_patent (
    record_version_id VARCHAR(96) NOT NULL, application_number VARCHAR(240), publication_number VARCHAR(240) NOT NULL,
    grant_publication_number VARCHAR(240), patent_type VARCHAR(80), application_date DATE,
    publication_date DATE, grant_date DATE, source_legal_status VARCHAR(300), source_ref VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_kb_patent PRIMARY KEY (record_version_id),
    CONSTRAINT fk_kb_patent_version FOREIGN KEY (record_version_id) REFERENCES kb_record_version (record_version_id)
);
CREATE TABLE kb_patent_title (
    record_version_id VARCHAR(96) NOT NULL, title_order INT NOT NULL, language_code VARCHAR(20) NOT NULL,
    title_text VARCHAR(2000) NOT NULL, derived_flag CHAR(1) NOT NULL,
    CONSTRAINT pk_kb_patent_title PRIMARY KEY (record_version_id, title_order),
    CONSTRAINT fk_kb_patent_title FOREIGN KEY (record_version_id) REFERENCES kb_patent (record_version_id)
);
CREATE TABLE kb_patent_party (
    record_version_id VARCHAR(96) NOT NULL, party_type VARCHAR(30) NOT NULL, party_order INT NOT NULL,
    party_name VARCHAR(1000) NOT NULL, address_text VARCHAR(2000),
    CONSTRAINT pk_kb_patent_party PRIMARY KEY (record_version_id, party_type, party_order),
    CONSTRAINT fk_kb_patent_party FOREIGN KEY (record_version_id) REFERENCES kb_patent (record_version_id)
);
CREATE TABLE kb_patent_classification (
    record_version_id VARCHAR(96) NOT NULL, scheme_code VARCHAR(20) NOT NULL, class_order INT NOT NULL,
    classification_code VARCHAR(120) NOT NULL, scheme_version VARCHAR(80),
    CONSTRAINT pk_kb_patent_class PRIMARY KEY (record_version_id, scheme_code, class_order),
    CONSTRAINT fk_kb_patent_class FOREIGN KEY (record_version_id) REFERENCES kb_patent (record_version_id)
);
CREATE TABLE kb_patent_priority (
    record_version_id VARCHAR(96) NOT NULL, priority_order INT NOT NULL, priority_number VARCHAR(240),
    priority_date DATE, country_code VARCHAR(20),
    CONSTRAINT pk_kb_patent_priority PRIMARY KEY (record_version_id, priority_order),
    CONSTRAINT fk_kb_patent_priority FOREIGN KEY (record_version_id) REFERENCES kb_patent (record_version_id)
);
CREATE TABLE kb_patent_document (
    document_id VARCHAR(96) NOT NULL, record_version_id VARCHAR(96) NOT NULL, document_type VARCHAR(40) NOT NULL,
    language_code VARCHAR(20), content_state VARCHAR(40) NOT NULL, document_text CLOB, external_ref VARCHAR(1000),
    content_sha256 VARCHAR(64), derived_flag CHAR(1) NOT NULL,
    CONSTRAINT pk_kb_patent_document PRIMARY KEY (document_id),
    CONSTRAINT fk_kb_patent_document FOREIGN KEY (record_version_id) REFERENCES kb_patent (record_version_id)
);
CREATE TABLE kb_patent_event (
    event_id VARCHAR(96) NOT NULL, record_version_id VARCHAR(96) NOT NULL, event_type VARCHAR(100) NOT NULL,
    business_date DATE, result_text CLOB, authority_name VARCHAR(300), source_ref VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_kb_patent_event PRIMARY KEY (event_id),
    CONSTRAINT fk_kb_patent_event FOREIGN KEY (record_version_id) REFERENCES kb_patent (record_version_id)
);

CREATE TABLE kb_trademark (
    record_version_id VARCHAR(96) NOT NULL, application_number VARCHAR(240), registration_number VARCHAR(240),
    mark_name VARCHAR(1000) NOT NULL, jurisdiction VARCHAR(40), filing_date DATE, registration_date DATE,
    valid_until DATE, source_status VARCHAR(300), source_ref VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_kb_trademark PRIMARY KEY (record_version_id),
    CONSTRAINT fk_kb_trademark_version FOREIGN KEY (record_version_id) REFERENCES kb_record_version (record_version_id)
);
CREATE TABLE kb_trademark_class (
    record_version_id VARCHAR(96) NOT NULL, class_order INT NOT NULL, class_code VARCHAR(20) NOT NULL,
    class_name VARCHAR(300), goods_services CLOB, similar_groups CLOB,
    CONSTRAINT pk_kb_trademark_class PRIMARY KEY (record_version_id, class_order),
    CONSTRAINT fk_kb_trademark_class FOREIGN KEY (record_version_id) REFERENCES kb_trademark (record_version_id)
);
CREATE TABLE kb_trademark_image (
    image_id VARCHAR(96) NOT NULL, record_version_id VARCHAR(96) NOT NULL, image_ref VARCHAR(1000) NOT NULL,
    media_type VARCHAR(120), content_sha256 VARCHAR(64), content_state VARCHAR(40) NOT NULL,
    CONSTRAINT pk_kb_trademark_image PRIMARY KEY (image_id),
    CONSTRAINT fk_kb_trademark_image FOREIGN KEY (record_version_id) REFERENCES kb_trademark (record_version_id)
);
CREATE TABLE kb_trademark_event (
    event_id VARCHAR(96) NOT NULL, record_version_id VARCHAR(96) NOT NULL, event_type VARCHAR(100) NOT NULL,
    business_date DATE, status_text VARCHAR(300), result_text CLOB, source_ref VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_kb_trademark_event PRIMARY KEY (event_id),
    CONSTRAINT fk_kb_trademark_event FOREIGN KEY (record_version_id) REFERENCES kb_trademark (record_version_id)
);

CREATE TABLE kb_case (
    record_version_id VARCHAR(96) NOT NULL, case_number VARCHAR(300), docket_number VARCHAR(300),
    title VARCHAR(2000) NOT NULL, case_type VARCHAR(200), ip_type VARCHAR(100), institution VARCHAR(500),
    procedure_code VARCHAR(100), hearing_date DATE, decision_date DATE, outcome_excerpt CLOB,
    source_ref VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_kb_case PRIMARY KEY (record_version_id),
    CONSTRAINT fk_kb_case_version FOREIGN KEY (record_version_id) REFERENCES kb_record_version (record_version_id)
);
CREATE TABLE kb_case_document (
    document_id VARCHAR(96) NOT NULL, record_version_id VARCHAR(96) NOT NULL, source_document_version VARCHAR(300) NOT NULL,
    content_state VARCHAR(40) NOT NULL, ocr_state VARCHAR(40), document_text CLOB, original_ref VARCHAR(1000),
    source_locator VARCHAR(1000), content_sha256 VARCHAR(64),
    CONSTRAINT pk_kb_case_document PRIMARY KEY (document_id),
    CONSTRAINT fk_kb_case_document FOREIGN KEY (record_version_id) REFERENCES kb_case (record_version_id)
);
CREATE TABLE kb_case_annotation (
    annotation_id VARCHAR(96) NOT NULL, record_version_id VARCHAR(96) NOT NULL, annotation_type VARCHAR(60) NOT NULL,
    annotation_text CLOB NOT NULL, source_locator VARCHAR(1000), method_name VARCHAR(120) NOT NULL,
    method_version VARCHAR(120), review_status VARCHAR(40) NOT NULL, derived_flag CHAR(1) NOT NULL,
    CONSTRAINT pk_kb_case_annotation PRIMARY KEY (annotation_id),
    CONSTRAINT fk_kb_case_annotation FOREIGN KEY (record_version_id) REFERENCES kb_case (record_version_id)
);

CREATE TABLE kb_regulation (
    record_version_id VARCHAR(96) NOT NULL, regulation_code VARCHAR(200), title VARCHAR(2000) NOT NULL,
    issuing_authority VARCHAR(500), publication_date DATE, effective_date DATE, expiry_date DATE,
    source_ref VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_kb_regulation PRIMARY KEY (record_version_id),
    CONSTRAINT fk_kb_regulation_version FOREIGN KEY (record_version_id) REFERENCES kb_record_version (record_version_id)
);
CREATE TABLE kb_regulation_article (
    article_id VARCHAR(96) NOT NULL, record_version_id VARCHAR(96) NOT NULL, article_order INT NOT NULL,
    article_number VARCHAR(100), heading VARCHAR(1000), article_text CLOB NOT NULL, source_locator VARCHAR(1000),
    CONSTRAINT pk_kb_regulation_article PRIMARY KEY (article_id),
    CONSTRAINT fk_kb_regulation_article FOREIGN KEY (record_version_id) REFERENCES kb_regulation (record_version_id)
);

CREATE INDEX ix_kb_version_lookup ON kb_record_version (domain_code, stable_record_key, imported_at);
CREATE INDEX ix_kb_patent_publication ON kb_patent (publication_number);
CREATE INDEX ix_kb_trademark_name ON kb_trademark (mark_name);
CREATE INDEX ix_kb_case_number ON kb_case (docket_number);
