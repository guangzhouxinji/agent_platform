CREATE TABLE con3_organization (
    organization_id VARCHAR(200) NOT NULL PRIMARY KEY,
    name VARCHAR(300) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_con3_org_status CHECK (status IN ('ACTIVE','DISABLED')),
    CONSTRAINT ck_con3_org_version CHECK (version >= 1)
);

CREATE TABLE con3_task (
    task_id VARCHAR(36) NOT NULL PRIMARY KEY,
    organization_id VARCHAR(200) NOT NULL,
    owner_issuer VARCHAR(300) NOT NULL,
    owner_subject VARCHAR(200) NOT NULL,
    state VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    retention_ref VARCHAR(200) NOT NULL,
    retention_version BIGINT NOT NULL,
    retention_evidence_sha VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_con3_task_org UNIQUE (task_id, organization_id),
    CONSTRAINT fk_con3_task_org FOREIGN KEY (organization_id) REFERENCES con3_organization(organization_id),
    CONSTRAINT fk_con3_task_owner FOREIGN KEY (owner_issuer, owner_subject)
        REFERENCES id2_subject(issuer, subject_id),
    CONSTRAINT ck_con3_task_state CHECK (state IN ('AWAITING_GOAL')),
    CONSTRAINT ck_con3_task_version CHECK (version >= 1 AND retention_version >= 1),
    CONSTRAINT ck_con3_task_expiry CHECK (expires_at > created_at)
);

CREATE TABLE con3_data_scope (
    scope_id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(200) NOT NULL,
    scope_version BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    CONSTRAINT uq_con3_scope_task UNIQUE (task_id),
    CONSTRAINT fk_con3_scope_task FOREIGN KEY (task_id, organization_id)
        REFERENCES con3_task(task_id, organization_id),
    CONSTRAINT ck_con3_scope_version CHECK (scope_version >= 1),
    CONSTRAINT ck_con3_scope_kind CHECK (kind = 'TASK_METADATA')
);

CREATE TABLE con3_initial_task (
    organization_id VARCHAR(200) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL UNIQUE,
    CONSTRAINT fk_con3_initial_org FOREIGN KEY (organization_id) REFERENCES con3_organization(organization_id),
    CONSTRAINT fk_con3_initial_task FOREIGN KEY (task_id, organization_id)
        REFERENCES con3_task(task_id, organization_id)
);

CREATE TABLE con3_initial_receipt (
    request_id VARCHAR(200) NOT NULL PRIMARY KEY,
    request_sha VARCHAR(64) NOT NULL,
    organization_id VARCHAR(200) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    scope_id VARCHAR(36) NOT NULL,
    grant_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con3_receipt_initial FOREIGN KEY (organization_id)
        REFERENCES con3_initial_task(organization_id),
    CONSTRAINT fk_con3_receipt_task FOREIGN KEY (task_id, organization_id)
        REFERENCES con3_task(task_id, organization_id),
    CONSTRAINT fk_con3_receipt_scope FOREIGN KEY (scope_id) REFERENCES con3_data_scope(scope_id),
    CONSTRAINT fk_con3_receipt_grant FOREIGN KEY (grant_id) REFERENCES id2_grant(grant_id)
);

CREATE TABLE con3_event (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    actor_ref VARCHAR(300) NOT NULL,
    version BIGINT NOT NULL,
    request_sha VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con3_event_task FOREIGN KEY (task_id) REFERENCES con3_task(task_id),
    CONSTRAINT ck_con3_event_operation CHECK (operation = 'INITIAL_CREATE'),
    CONSTRAINT ck_con3_event_version CHECK (version >= 1)
);
