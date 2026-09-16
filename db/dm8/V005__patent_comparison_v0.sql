CREATE TABLE con_patent_compare_task (
    task_id VARCHAR(64) NOT NULL,
    requester_ref VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    target_source_id VARCHAR(200) NOT NULL,
    target_version VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    data_mode VARCHAR(30) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    rule_version VARCHAR(100) NOT NULL,
    score_capability VARCHAR(40) NOT NULL,
    review_status VARCHAR(40) NOT NULL,
    limitations_text CLOB NOT NULL,
    trace_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_patent_compare_task PRIMARY KEY (task_id),
    CONSTRAINT uq_con_patent_compare_idem UNIQUE (requester_ref, idempotency_key),
    CONSTRAINT ck_con_patent_compare_purpose CHECK (purpose IN ('TECHNICAL_SIMILARITY', 'CLAIM_COMPARISON')),
    CONSTRAINT ck_con_patent_compare_status CHECK (status IN ('SUCCEEDED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_con_patent_compare_score CHECK (score_capability = 'NOT_CALIBRATED'),
    CONSTRAINT ck_con_patent_compare_review CHECK (review_status = 'UNREVIEWED')
);

CREATE TABLE con_patent_compare_candidate (
    task_id VARCHAR(64) NOT NULL,
    candidate_order INT NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    result_status VARCHAR(20) NOT NULL,
    similarities_text CLOB NOT NULL,
    differences_text CLOB NOT NULL,
    unexecuted_text CLOB NOT NULL,
    auxiliary_opinion CLOB NOT NULL,
    CONSTRAINT pk_con_patent_compare_candidate PRIMARY KEY (task_id, candidate_order),
    CONSTRAINT uq_con_patent_compare_source UNIQUE (task_id, source_id, source_version),
    CONSTRAINT fk_con_patent_compare_candidate FOREIGN KEY (task_id) REFERENCES con_patent_compare_task (task_id),
    CONSTRAINT ck_con_patent_candidate_order CHECK (candidate_order >= 0),
    CONSTRAINT ck_con_patent_candidate_status CHECK (result_status IN ('COMPLETED', 'FAILED'))
);

CREATE TABLE con_patent_compare_feature (
    task_id VARCHAR(64) NOT NULL,
    feature_order INT NOT NULL,
    feature_id VARCHAR(100) NOT NULL,
    target_text CLOB NOT NULL,
    source_location VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_con_patent_compare_feature PRIMARY KEY (task_id, feature_order),
    CONSTRAINT uq_con_patent_compare_feature UNIQUE (task_id, feature_id),
    CONSTRAINT fk_con_patent_compare_feature FOREIGN KEY (task_id) REFERENCES con_patent_compare_task (task_id),
    CONSTRAINT ck_con_patent_feature_order CHECK (feature_order >= 0)
);

CREATE TABLE con_patent_compare_mapping (
    task_id VARCHAR(64) NOT NULL,
    candidate_order INT NOT NULL,
    feature_order INT NOT NULL,
    relation_name VARCHAR(20) NOT NULL,
    candidate_evidence CLOB,
    candidate_location VARCHAR(1000),
    difference_text CLOB,
    reason_text CLOB NOT NULL,
    CONSTRAINT pk_con_patent_compare_mapping PRIMARY KEY (task_id, candidate_order, feature_order),
    CONSTRAINT fk_con_patent_mapping_candidate FOREIGN KEY (task_id, candidate_order) REFERENCES con_patent_compare_candidate (task_id, candidate_order),
    CONSTRAINT fk_con_patent_mapping_feature FOREIGN KEY (task_id, feature_order) REFERENCES con_patent_compare_feature (task_id, feature_order),
    CONSTRAINT ck_con_patent_mapping_relation CHECK (relation_name IN ('CORRESPONDS', 'NOT_FOUND'))
);

CREATE INDEX ix_con_patent_compare_requester ON con_patent_compare_task (requester_ref, created_at);
CREATE INDEX ix_con_patent_compare_target ON con_patent_compare_task (target_source_id, target_version);
CREATE INDEX ix_con_patent_compare_candidate_source ON con_patent_compare_candidate (source_id, source_version);
