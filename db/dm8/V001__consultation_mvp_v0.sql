CREATE TABLE con_consultation (
    consultation_id VARCHAR(36) NOT NULL,
    requester_ref VARCHAR(200) NOT NULL,
    scope_code VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_consultation PRIMARY KEY (consultation_id)
);

CREATE TABLE con_source_locator (
    source_id VARCHAR(36) NOT NULL,
    source_kind VARCHAR(40) NOT NULL,
    source_system VARCHAR(120) NOT NULL,
    source_object_id VARCHAR(200) NOT NULL,
    version_ref VARCHAR(200),
    location_text VARCHAR(1000) NOT NULL,
    retrieved_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_source_locator PRIMARY KEY (source_id)
);

CREATE TABLE con_user_input (
    input_id VARCHAR(36) NOT NULL,
    consultation_id VARCHAR(36) NOT NULL,
    input_sequence INT NOT NULL,
    content CLOB NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_user_input PRIMARY KEY (input_id),
    CONSTRAINT uq_con_user_input_sequence UNIQUE (consultation_id, input_sequence),
    CONSTRAINT fk_con_user_input_consultation FOREIGN KEY (consultation_id) REFERENCES con_consultation (consultation_id),
    CONSTRAINT fk_con_user_input_source FOREIGN KEY (source_id) REFERENCES con_source_locator (source_id)
);

CREATE TABLE con_information_issue (
    issue_id VARCHAR(36) NOT NULL,
    consultation_id VARCHAR(36) NOT NULL,
    issue_type VARCHAR(40) NOT NULL,
    description_text VARCHAR(1000) NOT NULL,
    related_source_id VARCHAR(36) NOT NULL,
    resolved_by_source_id VARCHAR(36),
    CONSTRAINT pk_con_information_issue PRIMARY KEY (issue_id),
    CONSTRAINT fk_con_issue_consultation FOREIGN KEY (consultation_id) REFERENCES con_consultation (consultation_id),
    CONSTRAINT fk_con_issue_related_source FOREIGN KEY (related_source_id) REFERENCES con_source_locator (source_id),
    CONSTRAINT fk_con_issue_resolved_source FOREIGN KEY (resolved_by_source_id) REFERENCES con_source_locator (source_id)
);

CREATE TABLE con_retrieval_request (
    retrieval_request_id VARCHAR(36) NOT NULL,
    consultation_id VARCHAR(36) NOT NULL,
    confirmed_input CLOB NOT NULL,
    purpose VARCHAR(50) NOT NULL,
    requested_scope CLOB,
    access_outcome VARCHAR(20) NOT NULL,
    access_decision_source_id VARCHAR(36),
    access_checked_at TIMESTAMP NOT NULL,
    execution_outcome VARCHAR(20) NOT NULL,
    actual_scope CLOB,
    failure_category VARCHAR(40),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_retrieval_request PRIMARY KEY (retrieval_request_id),
    CONSTRAINT fk_con_request_consultation FOREIGN KEY (consultation_id) REFERENCES con_consultation (consultation_id),
    CONSTRAINT fk_con_request_decision_source FOREIGN KEY (access_decision_source_id) REFERENCES con_source_locator (source_id),
    CONSTRAINT ck_con_request_access CHECK (access_outcome IN ('ALLOWED', 'DENIED', 'INDETERMINATE')),
    CONSTRAINT ck_con_request_execution CHECK (execution_outcome IN ('NOT_EXECUTED', 'COMPLETED', 'PARTIAL', 'FAILED'))
);

CREATE TABLE con_retrieval_candidate (
    candidate_id VARCHAR(36) NOT NULL,
    retrieval_request_id VARCHAR(36) NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    display_summary CLOB NOT NULL,
    match_basis CLOB NOT NULL,
    ordering_basis VARCHAR(500),
    source_use_outcome VARCHAR(30) NOT NULL,
    unavailable_reason VARCHAR(200),
    CONSTRAINT pk_con_retrieval_candidate PRIMARY KEY (candidate_id),
    CONSTRAINT fk_con_candidate_request FOREIGN KEY (retrieval_request_id) REFERENCES con_retrieval_request (retrieval_request_id),
    CONSTRAINT fk_con_candidate_source FOREIGN KEY (source_id) REFERENCES con_source_locator (source_id),
    CONSTRAINT ck_con_candidate_source_use CHECK (source_use_outcome IN ('USABLE', 'UNAVAILABLE'))
);

CREATE TABLE con_assisted_answer (
    answer_id VARCHAR(36) NOT NULL,
    consultation_id VARCHAR(36) NOT NULL,
    retrieval_request_id VARCHAR(36),
    outcome_type VARCHAR(40) NOT NULL,
    content CLOB NOT NULL,
    limitations CLOB,
    professional_review_required CHAR(1) NOT NULL,
    professional_review_reason VARCHAR(1000),
    answer_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_assisted_answer PRIMARY KEY (answer_id),
    CONSTRAINT uq_con_answer_version UNIQUE (consultation_id, answer_version),
    CONSTRAINT fk_con_answer_consultation FOREIGN KEY (consultation_id) REFERENCES con_consultation (consultation_id),
    CONSTRAINT fk_con_answer_request FOREIGN KEY (retrieval_request_id) REFERENCES con_retrieval_request (retrieval_request_id),
    CONSTRAINT ck_con_answer_review CHECK (professional_review_required IN ('Y', 'N'))
);

CREATE TABLE con_answer_candidate (
    answer_id VARCHAR(36) NOT NULL,
    candidate_id VARCHAR(36) NOT NULL,
    CONSTRAINT pk_con_answer_candidate PRIMARY KEY (answer_id, candidate_id),
    CONSTRAINT fk_con_answer_candidate_answer FOREIGN KEY (answer_id) REFERENCES con_assisted_answer (answer_id),
    CONSTRAINT fk_con_answer_candidate_candidate FOREIGN KEY (candidate_id) REFERENCES con_retrieval_candidate (candidate_id)
);

CREATE TABLE con_answer_source_link (
    answer_source_link_id VARCHAR(36) NOT NULL,
    answer_id VARCHAR(36) NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    content_location VARCHAR(1000),
    CONSTRAINT pk_con_answer_source_link PRIMARY KEY (answer_source_link_id),
    CONSTRAINT uq_con_answer_source_link UNIQUE (answer_id, source_id, content_location),
    CONSTRAINT fk_con_answer_source_answer FOREIGN KEY (answer_id) REFERENCES con_assisted_answer (answer_id),
    CONSTRAINT fk_con_answer_source_locator FOREIGN KEY (source_id) REFERENCES con_source_locator (source_id)
);

CREATE TABLE con_idempotency_record (
    requester_ref VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    consultation_id VARCHAR(36) NOT NULL,
    answer_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_idempotency_record PRIMARY KEY (requester_ref, idempotency_key),
    CONSTRAINT fk_con_idempotency_consultation FOREIGN KEY (consultation_id) REFERENCES con_consultation (consultation_id),
    CONSTRAINT fk_con_idempotency_answer FOREIGN KEY (answer_id) REFERENCES con_assisted_answer (answer_id)
);

CREATE TABLE con_patent_search_task (
    task_id VARCHAR(36) NOT NULL,
    requester_ref VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    input_mode VARCHAR(40) NOT NULL,
    input_text CLOB NOT NULL,
    purpose VARCHAR(200) NOT NULL,
    confirmed_features CLOB,
    patent_types CLOB,
    regions CLOB,
    classifications CLOB,
    application_from DATE,
    application_to DATE,
    publication_from DATE,
    publication_to DATE,
    status VARCHAR(20) NOT NULL,
    attempt INT NOT NULL,
    data_mode VARCHAR(30) NOT NULL,
    scoring_version VARCHAR(100) NOT NULL,
    actual_scope CLOB,
    limitation CLOB,
    error_code VARCHAR(100),
    trace_id VARCHAR(100) NOT NULL,
    row_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_patent_search_task PRIMARY KEY (task_id),
    CONSTRAINT uq_con_patent_search_idem UNIQUE (requester_ref, idempotency_key),
    CONSTRAINT ck_con_patent_search_status CHECK (status IN ('QUEUED', 'PROCESSING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED'))
);

CREATE TABLE con_patent_search_candidate (
    task_id VARCHAR(36) NOT NULL,
    candidate_order INT NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    application_number VARCHAR(100) NOT NULL,
    publication_number VARCHAR(100) NOT NULL,
    title VARCHAR(500) NOT NULL,
    patent_type VARCHAR(100) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    score INT NOT NULL,
    relevance_label VARCHAR(100) NOT NULL,
    matched_features CLOB,
    matching_excerpt CLOB NOT NULL,
    location_text VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_con_patent_search_candidate PRIMARY KEY (task_id, candidate_order),
    CONSTRAINT uq_con_patent_search_source UNIQUE (task_id, source_id),
    CONSTRAINT fk_con_patent_search_task FOREIGN KEY (task_id) REFERENCES con_patent_search_task (task_id)
);

CREATE INDEX ix_con_input_consultation ON con_user_input (consultation_id, input_sequence);
CREATE INDEX ix_con_issue_consultation ON con_information_issue (consultation_id);
CREATE INDEX ix_con_request_consultation ON con_retrieval_request (consultation_id, created_at);
CREATE INDEX ix_con_candidate_request ON con_retrieval_candidate (retrieval_request_id);
CREATE INDEX ix_con_answer_consultation ON con_assisted_answer (consultation_id, answer_version);
CREATE INDEX ix_con_patent_search_owner ON con_patent_search_task (requester_ref, created_at);
CREATE INDEX ix_con_patent_search_status ON con_patent_search_task (status, updated_at);

CREATE TABLE con_trademark_search_task (
    task_id VARCHAR(36) NOT NULL, requester_ref VARCHAR(200) NOT NULL, idempotency_key VARCHAR(200) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL, original_name VARCHAR(500) NOT NULL, normalized_name VARCHAR(500) NOT NULL,
    categories CLOB NOT NULL, goods_services CLOB NOT NULL, dimensions CLOB NOT NULL, statuses CLOB, regions CLOB, data_sources CLOB,
    page_no INT NOT NULL, page_size INT NOT NULL, status VARCHAR(20) NOT NULL, attempt INT NOT NULL,
    data_mode VARCHAR(30) NOT NULL, ranking_version VARCHAR(100) NOT NULL, actual_scope CLOB, total_candidates INT NOT NULL,
    truncated INT NOT NULL, limitation CLOB, error_code VARCHAR(100), trace_id VARCHAR(100) NOT NULL, row_version INT NOT NULL,
    created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_trademark_search_task PRIMARY KEY (task_id),
    CONSTRAINT uq_con_trademark_search_idem UNIQUE (requester_ref, idempotency_key),
    CONSTRAINT ck_con_trademark_search_status CHECK (status IN ('QUEUED', 'PROCESSING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_con_trademark_search_page CHECK (page_no > 0 AND page_size BETWEEN 1 AND 100),
    CONSTRAINT ck_con_trademark_search_truncated CHECK (truncated IN (0, 1))
);

CREATE TABLE con_trademark_search_candidate (
    task_id VARCHAR(36) NOT NULL, candidate_order INT NOT NULL, source_id VARCHAR(200) NOT NULL,
    registration_number VARCHAR(100) NOT NULL, name VARCHAR(500) NOT NULL, categories CLOB NOT NULL, goods_services CLOB NOT NULL,
    source_status VARCHAR(100) NOT NULL, region VARCHAR(50) NOT NULL, data_source VARCHAR(100) NOT NULL,
    source_version VARCHAR(100) NOT NULL, source_updated_at VARCHAR(100) NOT NULL, relevance_label VARCHAR(100) NOT NULL,
    CONSTRAINT pk_con_trademark_search_candidate PRIMARY KEY (task_id, candidate_order),
    CONSTRAINT uq_con_trademark_search_source UNIQUE (task_id, source_id),
    CONSTRAINT fk_con_trademark_search_candidate_task FOREIGN KEY (task_id) REFERENCES con_trademark_search_task (task_id)
);

CREATE TABLE con_trademark_search_dimension (
    task_id VARCHAR(36) NOT NULL, candidate_order INT NOT NULL, dimension_order INT NOT NULL, dimension_name VARCHAR(20) NOT NULL,
    dimension_state VARCHAR(20) NOT NULL, basis CLOB NOT NULL,
    CONSTRAINT pk_con_trademark_search_dimension PRIMARY KEY (task_id, candidate_order, dimension_order),
    CONSTRAINT uq_con_trademark_search_dimension_name UNIQUE (task_id, candidate_order, dimension_name),
    CONSTRAINT fk_con_trademark_search_dimension_candidate FOREIGN KEY (task_id, candidate_order) REFERENCES con_trademark_search_candidate (task_id, candidate_order),
    CONSTRAINT ck_con_trademark_search_dimension CHECK (dimension_name IN ('TEXT', 'PHONETIC', 'SEMANTIC') AND dimension_state IN ('EXECUTED', 'NOT_ANALYZED'))
);

CREATE INDEX ix_con_trademark_search_owner ON con_trademark_search_task (requester_ref, created_at);
CREATE INDEX ix_con_trademark_search_status ON con_trademark_search_task (status, updated_at);
