CREATE TABLE con_case_extract_task (
    task_id VARCHAR(64) NOT NULL,
    requester_ref VARCHAR(200) NOT NULL,
    case_id VARCHAR(200) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    extract_config VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL,
    annotation_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    trace_id VARCHAR(100) NOT NULL,
    error_code VARCHAR(100),
    row_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_case_extract_task PRIMARY KEY (task_id),
    CONSTRAINT uq_con_case_extract_idem UNIQUE (requester_ref, case_id, source_version, extract_config),
    CONSTRAINT uq_con_case_extract_annotation UNIQUE (annotation_id),
    CONSTRAINT ck_con_case_extract_status CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED'))
);

CREATE TABLE con_case_annotation (
    annotation_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(200) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    current_version INT NOT NULL,
    current_state VARCHAR(30) NOT NULL,
    row_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_case_annotation PRIMARY KEY (annotation_id),
    CONSTRAINT fk_con_case_annotation_task FOREIGN KEY (annotation_id) REFERENCES con_case_extract_task (annotation_id),
    CONSTRAINT ck_con_case_annotation_version CHECK (current_version > 0),
    CONSTRAINT ck_con_case_annotation_state CHECK (current_state IN ('DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'RETURNED', 'WITHDRAWN'))
);

CREATE TABLE con_case_annotation_version (
    annotation_id VARCHAR(64) NOT NULL,
    annotation_version INT NOT NULL,
    annotation_state VARCHAR(30) NOT NULL,
    fact_text CLOB NOT NULL,
    issue_text CLOB NOT NULL,
    holding_text CLOB NOT NULL,
    source_location VARCHAR(1000) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL,
    reason_text VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_case_annotation_version PRIMARY KEY (annotation_id, annotation_version),
    CONSTRAINT fk_con_case_version_annotation FOREIGN KEY (annotation_id) REFERENCES con_case_annotation (annotation_id),
    CONSTRAINT ck_con_case_version_number CHECK (annotation_version > 0),
    CONSTRAINT ck_con_case_version_state CHECK (annotation_state IN ('DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'RETURNED', 'WITHDRAWN'))
);

CREATE TABLE con_case_annotation_audit (
    audit_id VARCHAR(36) NOT NULL,
    annotation_id VARCHAR(64) NOT NULL,
    annotation_version INT NOT NULL,
    action_name VARCHAR(30) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL,
    reason_text VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_case_annotation_audit PRIMARY KEY (audit_id),
    CONSTRAINT fk_con_case_audit_version FOREIGN KEY (annotation_id, annotation_version) REFERENCES con_case_annotation_version (annotation_id, annotation_version),
    CONSTRAINT ck_con_case_audit_action CHECK (action_name IN ('EXTRACT', 'REVISE', 'SUBMIT', 'PUBLISH', 'RETURN', 'WITHDRAW'))
);

CREATE INDEX ix_con_case_extract_status ON con_case_extract_task (status, updated_at);
CREATE INDEX ix_con_case_annotation_case ON con_case_annotation (case_id, source_version);
CREATE INDEX ix_con_case_version_created ON con_case_annotation_version (annotation_id, created_at);
CREATE INDEX ix_con_case_audit_created ON con_case_annotation_audit (annotation_id, created_at);
