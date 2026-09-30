CREATE TABLE con4_task_source_scope (
    scope_id VARCHAR(36) NOT NULL,
    scope_version BIGINT NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(200) NOT NULL,
    task_version BIGINT NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    contract_version VARCHAR(100) NOT NULL,
    decision_id VARCHAR(36) NOT NULL,
    source_epoch BIGINT NOT NULL,
    source_row_version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (scope_id, scope_version),
    CONSTRAINT uq_con4_scope_exact UNIQUE (scope_id, scope_version, task_id, source_id),
    CONSTRAINT fk_con4_scope_task FOREIGN KEY (task_id, organization_id)
        REFERENCES con3_task(task_id, organization_id),
    CONSTRAINT fk_con4_scope_contract FOREIGN KEY (source_id, contract_version)
        REFERENCES con4_source_contract(source_id, contract_version),
    CONSTRAINT fk_con4_scope_decision FOREIGN KEY (decision_id, source_id, contract_version)
        REFERENCES con4_source_admission(decision_id, source_id, contract_version),
    CONSTRAINT ck_con4_scope_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT ck_con4_scope_versions CHECK (scope_version > 0 AND task_version > 0
        AND source_epoch > 0 AND source_row_version > 0),
    CONSTRAINT ck_con4_scope_expiry CHECK (expires_at > created_at)
);

CREATE TABLE con4_task_source_current (
    task_id VARCHAR(36) NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    scope_id VARCHAR(36) NOT NULL,
    scope_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    PRIMARY KEY (task_id, source_id),
    CONSTRAINT fk_con4_current_scope FOREIGN KEY (scope_id, scope_version, task_id, source_id)
        REFERENCES con4_task_source_scope(scope_id, scope_version, task_id, source_id),
    CONSTRAINT ck_con4_current_scope_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT ck_con4_current_scope_version CHECK (row_version > 0)
);

CREATE TABLE con4_task_source_event (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    scope_id VARCHAR(36) NOT NULL,
    scope_version BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    actor_ref VARCHAR(300) NOT NULL,
    request_sha VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con4_scope_event FOREIGN KEY (scope_id, scope_version)
        REFERENCES con4_task_source_scope(scope_id, scope_version),
    CONSTRAINT ck_con4_scope_operation CHECK (operation IN ('GRANT','REVOKE'))
);

CREATE TABLE con4_task_source_request (
    request_id VARCHAR(200) NOT NULL PRIMARY KEY,
    request_sha VARCHAR(64) NOT NULL,
    event_id VARCHAR(36) NOT NULL UNIQUE,
    query_grant_id VARCHAR(36),
    display_grant_id VARCHAR(36),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con4_scope_request_event FOREIGN KEY (event_id)
        REFERENCES con4_task_source_event(event_id),
    CONSTRAINT fk_con4_scope_query_grant FOREIGN KEY (query_grant_id)
        REFERENCES id2_grant(grant_id),
    CONSTRAINT fk_con4_scope_display_grant FOREIGN KEY (display_grant_id)
        REFERENCES id2_grant(grant_id),
    CONSTRAINT ck_con4_scope_grant_pair CHECK (
        (query_grant_id IS NULL AND display_grant_id IS NULL) OR
        (query_grant_id IS NOT NULL AND display_grant_id IS NOT NULL))
);

CREATE INDEX ix_con4_scope_task ON con4_task_source_scope(task_id, organization_id);
