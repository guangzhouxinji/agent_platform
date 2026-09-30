CREATE TABLE con4_search_attempt (
    attempt_id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(200) NOT NULL,
    task_version BIGINT NOT NULL,
    input_revision VARCHAR(100) NOT NULL,
    input_sha VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(300) NOT NULL,
    request_sha VARCHAR(64) NOT NULL,
    original_query VARCHAR(4000) NOT NULL,
    actual_query VARCHAR(4000) NOT NULL,
    scope_sha VARCHAR(64) NOT NULL,
    method_version VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    retention_ref VARCHAR(200) NOT NULL,
    retention_version BIGINT NOT NULL,
    retention_evidence_sha VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con4_search_task FOREIGN KEY (task_id, organization_id)
        REFERENCES con3_task(task_id, organization_id),
    CONSTRAINT ck_con4_search_status CHECK (status IN ('SUCCEEDED','PARTIAL','FAILED')),
    CONSTRAINT ck_con4_search_versions CHECK (task_version > 0 AND retention_version > 0),
    CONSTRAINT ck_con4_search_expiry CHECK (expires_at > created_at)
);

CREATE TABLE con4_search_source (
    attempt_id VARCHAR(36) NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    contract_version VARCHAR(100) NOT NULL,
    decision_id VARCHAR(36) NOT NULL,
    source_epoch BIGINT NOT NULL,
    coverage_sha VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(100),
    PRIMARY KEY (attempt_id, source_id),
    CONSTRAINT uq_con4_search_source_version UNIQUE (attempt_id, source_id, contract_version),
    CONSTRAINT fk_con4_search_source_attempt FOREIGN KEY (attempt_id)
        REFERENCES con4_search_attempt(attempt_id),
    CONSTRAINT fk_con4_search_source_decision FOREIGN KEY (decision_id, source_id, contract_version)
        REFERENCES con4_source_admission(decision_id, source_id, contract_version),
    CONSTRAINT ck_con4_search_source_epoch CHECK (source_epoch > 0),
    CONSTRAINT ck_con4_search_source_status CHECK (status IN ('SUCCEEDED','FAILED')),
    CONSTRAINT ck_con4_search_source_error CHECK ((status='SUCCEEDED' AND error_code IS NULL)
        OR (status='FAILED' AND error_code IS NOT NULL))
);

CREATE TABLE con4_result_set (
    result_set_id VARCHAR(36) NOT NULL PRIMARY KEY,
    attempt_id VARCHAR(36) NOT NULL UNIQUE,
    ordering_version VARCHAR(100) NOT NULL,
    truncated INTEGER NOT NULL,
    total_reliability VARCHAR(16) NOT NULL,
    result_sha VARCHAR(64) NOT NULL,
    CONSTRAINT uq_con4_result_attempt_pair UNIQUE (result_set_id, attempt_id),
    CONSTRAINT fk_con4_result_attempt FOREIGN KEY (attempt_id)
        REFERENCES con4_search_attempt(attempt_id),
    CONSTRAINT ck_con4_result_truncated CHECK (truncated IN (0,1)),
    CONSTRAINT ck_con4_result_reliability CHECK (total_reliability IN ('EXACT','LOWER_BOUND','UNKNOWN'))
);

CREATE TABLE con4_result_member (
    result_set_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(36) NOT NULL,
    ordinal BIGINT NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    contract_version VARCHAR(100) NOT NULL,
    document_id VARCHAR(300) NOT NULL,
    document_version VARCHAR(200) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    raw_sha VARCHAR(64) NOT NULL,
    locator_ref VARCHAR(1000) NOT NULL,
    match_fields VARCHAR(1000) NOT NULL,
    match_sha VARCHAR(64) NOT NULL,
    PRIMARY KEY (result_set_id, member_id),
    CONSTRAINT uq_con4_result_ordinal UNIQUE (result_set_id, ordinal),
    CONSTRAINT uq_con4_result_document UNIQUE (result_set_id, source_id, document_id, document_version),
    CONSTRAINT fk_con4_member_set FOREIGN KEY (result_set_id, attempt_id)
        REFERENCES con4_result_set(result_set_id, attempt_id),
    CONSTRAINT fk_con4_member_search_source FOREIGN KEY (attempt_id, source_id, contract_version)
        REFERENCES con4_search_source(attempt_id, source_id, contract_version),
    CONSTRAINT fk_con4_member_contract FOREIGN KEY (source_id, contract_version)
        REFERENCES con4_source_contract(source_id, contract_version),
    CONSTRAINT ck_con4_result_ordinal CHECK (ordinal >= 0)
);

CREATE TABLE con4_result_cursor (
    cursor_id VARCHAR(36) NOT NULL PRIMARY KEY,
    result_set_id VARCHAR(36) NOT NULL,
    next_ordinal BIGINT NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    task_version BIGINT NOT NULL,
    input_revision VARCHAR(100) NOT NULL,
    scope_sha VARCHAR(64) NOT NULL,
    source_epoch_sha VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con4_cursor_set FOREIGN KEY (result_set_id)
        REFERENCES con4_result_set(result_set_id),
    CONSTRAINT ck_con4_cursor_versions CHECK (next_ordinal >= 0 AND task_version > 0)
);

CREATE TABLE con4_search_request (
    request_id VARCHAR(200) NOT NULL PRIMARY KEY,
    request_sha VARCHAR(64) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL UNIQUE,
    result_set_id VARCHAR(36) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con4_request_attempt FOREIGN KEY (attempt_id)
        REFERENCES con4_search_attempt(attempt_id),
    CONSTRAINT fk_con4_request_result FOREIGN KEY (result_set_id, attempt_id)
        REFERENCES con4_result_set(result_set_id, attempt_id)
);

CREATE INDEX ix_con4_search_task ON con4_search_attempt(task_id, created_at);
CREATE INDEX ix_con4_member_document ON con4_result_member(source_id, document_id, document_version);
