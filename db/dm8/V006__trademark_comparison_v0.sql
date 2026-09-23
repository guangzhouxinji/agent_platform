CREATE TABLE con_trademark_compare_task (
    task_id VARCHAR(64) NOT NULL,
    requester_ref VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    target_source_id VARCHAR(200) NOT NULL,
    target_version VARCHAR(100) NOT NULL,
    confirmed_categories CLOB NOT NULL,
    confirmed_goods_services CLOB NOT NULL,
    selected_dimensions CLOB NOT NULL,
    status VARCHAR(20) NOT NULL,
    data_mode VARCHAR(30) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    rule_version VARCHAR(100) NOT NULL,
    report_capability VARCHAR(40) NOT NULL,
    review_status VARCHAR(40) NOT NULL,
    limitations_text CLOB NOT NULL,
    trace_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_trademark_compare_task PRIMARY KEY (task_id),
    CONSTRAINT uq_con_trademark_compare_idem UNIQUE (requester_ref, idempotency_key),
    CONSTRAINT ck_con_trademark_compare_purpose CHECK (purpose = 'AUXILIARY_COMPARISON'),
    CONSTRAINT ck_con_trademark_compare_status CHECK (status IN ('SUCCEEDED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_con_trademark_compare_report CHECK (report_capability = 'EVIDENCE_TABLE_ONLY'),
    CONSTRAINT ck_con_trademark_compare_review CHECK (review_status = 'UNREVIEWED')
);

CREATE TABLE con_trademark_compare_candidate (
    task_id VARCHAR(64) NOT NULL,
    candidate_order INT NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    result_status VARCHAR(20) NOT NULL,
    missing_items_text CLOB NOT NULL,
    auxiliary_opinion CLOB NOT NULL,
    CONSTRAINT pk_con_trademark_compare_candidate PRIMARY KEY (task_id, candidate_order),
    CONSTRAINT uq_con_trademark_compare_source UNIQUE (task_id, source_id, source_version),
    CONSTRAINT fk_con_trademark_compare_candidate FOREIGN KEY (task_id) REFERENCES con_trademark_compare_task (task_id),
    CONSTRAINT ck_con_trademark_candidate_order CHECK (candidate_order >= 0),
    CONSTRAINT ck_con_trademark_candidate_status CHECK (result_status IN ('COMPLETED', 'PARTIAL', 'FAILED'))
);

CREATE TABLE con_trademark_compare_dimension (
    task_id VARCHAR(64) NOT NULL,
    candidate_order INT NOT NULL,
    dimension_order INT NOT NULL,
    dimension_name VARCHAR(40) NOT NULL,
    result_state VARCHAR(40) NOT NULL,
    observation_text CLOB NOT NULL,
    evidence_text CLOB NOT NULL,
    reason_text CLOB NOT NULL,
    CONSTRAINT pk_con_trademark_compare_dimension PRIMARY KEY (task_id, candidate_order, dimension_order),
    CONSTRAINT uq_con_trademark_compare_dimension UNIQUE (task_id, candidate_order, dimension_name),
    CONSTRAINT fk_con_trademark_compare_dimension FOREIGN KEY (task_id, candidate_order) REFERENCES con_trademark_compare_candidate (task_id, candidate_order),
    CONSTRAINT ck_con_trademark_dimension_order CHECK (dimension_order >= 0),
    CONSTRAINT ck_con_trademark_dimension_name CHECK (dimension_name IN ('TEXT', 'PHONETIC', 'SEMANTIC', 'IMAGE', 'OVERALL_VISUAL', 'GOODS_SERVICES')),
    CONSTRAINT ck_con_trademark_dimension_state CHECK (result_state IN ('ANALYZED', 'NOT_ANALYZED', 'MATERIAL_UNAVAILABLE'))
);

CREATE INDEX ix_con_trademark_compare_requester ON con_trademark_compare_task (requester_ref, created_at);
CREATE INDEX ix_con_trademark_compare_target ON con_trademark_compare_task (target_source_id, target_version);
CREATE INDEX ix_con_trademark_compare_source ON con_trademark_compare_candidate (source_id, source_version);
