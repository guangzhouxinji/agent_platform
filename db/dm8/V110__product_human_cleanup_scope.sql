CREATE TABLE con6_human_cleanup_scope (
    scope_id VARCHAR(36) PRIMARY KEY,
    organization_id VARCHAR(200) NOT NULL,
    task_id VARCHAR(36) NOT NULL UNIQUE,
    generation BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    intent_id VARCHAR(36),
    policy_id VARCHAR(200) NOT NULL,
    policy_version BIGINT NOT NULL,
    policy_sha VARCHAR(64) NOT NULL,
    history_sha VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    valid_until TIMESTAMP NOT NULL,
    CONSTRAINT fk_con6_cleanup_task FOREIGN KEY(task_id,organization_id) REFERENCES con3_task(task_id,organization_id),
    CONSTRAINT ck_con6_cleanup_scope CHECK(generation>0 AND policy_version>0 AND valid_until>created_at
        AND ((state='READY' AND intent_id IS NULL) OR (state='PENDING' AND intent_id IS NOT NULL)))
);
CREATE TABLE con6_human_cleanup_event (
    scope_id VARCHAR(36) NOT NULL,
    generation BIGINT NOT NULL,
    event_json CLOB NOT NULL,
    event_sha VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    audit_until TIMESTAMP NOT NULL,
    PRIMARY KEY(scope_id,generation),
    CONSTRAINT fk_con6_cleanup_event FOREIGN KEY(scope_id) REFERENCES con6_human_cleanup_scope(scope_id),
    CONSTRAINT ck_con6_cleanup_event CHECK(generation>0 AND audit_until>occurred_at)
);
