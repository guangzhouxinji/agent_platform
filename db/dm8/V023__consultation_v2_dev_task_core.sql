CREATE TABLE con2_task (
    task_id VARCHAR(36) NOT NULL,
    org_ref VARCHAR(200) NOT NULL,
    owner_ref VARCHAR(200) NOT NULL,
    state VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL,
    cancel_epoch BIGINT NOT NULL,
    last_business_change_at TIMESTAMP NOT NULL,
    terminated_at TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    prior_task_id VARCHAR(36),
    CONSTRAINT pk_con2_task PRIMARY KEY (task_id),
    CONSTRAINT ck_con2_task_state CHECK (state IN ('ACTIVE','CANCELLED','PURGING')),
    CONSTRAINT ck_con2_task_revision CHECK (revision >= 1 AND cancel_epoch >= 0),
    CONSTRAINT ck_con2_task_termination CHECK (
        (state = 'ACTIVE' AND terminated_at IS NULL)
        OR (state = 'CANCELLED' AND terminated_at IS NOT NULL)
        OR state = 'PURGING')
);
CREATE INDEX ix_con2_task_owner ON con2_task(org_ref, owner_ref, expires_at);
CREATE INDEX ix_con2_task_retention ON con2_task(state, expires_at, task_id);

CREATE TABLE con2_message (
    task_id VARCHAR(36) NOT NULL,
    seq BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    actor_ref VARCHAR(200) NOT NULL,
    body CLOB NOT NULL,
    body_sha CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_message PRIMARY KEY (task_id, seq),
    CONSTRAINT uq_con2_message_revision UNIQUE (task_id, revision),
    CONSTRAINT fk_con2_message_task FOREIGN KEY (task_id) REFERENCES con2_task(task_id)
);

CREATE TABLE con2_receipt (
    receipt_id VARCHAR(36) NOT NULL,
    scope_sha CHAR(64) NOT NULL,
    idem_sha CHAR(64) NOT NULL,
    request_sha CHAR(64) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    committed_revision BIGINT NOT NULL,
    committed_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_receipt PRIMARY KEY (receipt_id),
    CONSTRAINT uq_con2_receipt_key UNIQUE (scope_sha, idem_sha),
    CONSTRAINT fk_con2_receipt_task FOREIGN KEY (task_id) REFERENCES con2_task(task_id)
);
CREATE INDEX ix_con2_receipt_task ON con2_receipt(task_id);

CREATE TABLE con2_attempt (
    attempt_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    input_revision BIGINT NOT NULL,
    cancel_epoch BIGINT NOT NULL,
    delegated_owner_ref VARCHAR(200) NOT NULL,
    authorization_version BIGINT NOT NULL,
    delegation_until TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_attempt PRIMARY KEY (attempt_id),
    CONSTRAINT uq_con2_attempt_task UNIQUE (task_id, attempt_id),
    CONSTRAINT ck_con2_attempt_status CHECK (status IN ('PENDING','RUNNING','CHECKPOINTED','STOPPED')),
    CONSTRAINT fk_con2_attempt_task FOREIGN KEY (task_id) REFERENCES con2_task(task_id)
);
CREATE INDEX ix_con2_attempt_task ON con2_attempt(task_id, input_revision);

CREATE TABLE con2_lease (
    task_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    fencing_token BIGINT NOT NULL,
    lease_until TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_lease PRIMARY KEY (task_id),
    CONSTRAINT fk_con2_lease_attempt FOREIGN KEY (task_id, attempt_id)
        REFERENCES con2_attempt(task_id, attempt_id)
);
CREATE INDEX ix_con2_lease_until ON con2_lease(lease_until);

CREATE TABLE con2_checkpoint (
    attempt_id VARCHAR(36) NOT NULL,
    seq BIGINT NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    task_revision BIGINT NOT NULL,
    cancel_epoch BIGINT NOT NULL,
    fencing_token BIGINT NOT NULL,
    committed_ref VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_checkpoint PRIMARY KEY (attempt_id, seq),
    CONSTRAINT fk_con2_checkpoint_attempt FOREIGN KEY (task_id, attempt_id)
        REFERENCES con2_attempt(task_id, attempt_id)
);
CREATE INDEX ix_con2_checkpoint_task ON con2_checkpoint(task_id, attempt_id, seq);

CREATE TABLE con2_event (
    task_id VARCHAR(36) NOT NULL,
    seq BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    task_revision BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    event_ref VARCHAR(100),
    CONSTRAINT pk_con2_event PRIMARY KEY (task_id, seq),
    CONSTRAINT fk_con2_event_task FOREIGN KEY (task_id) REFERENCES con2_task(task_id)
);
