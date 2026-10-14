-- Immutable dispatch fence, not a model answer or a new business state.
CREATE TABLE con3_model_invocation (
    invocation_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    task_version BIGINT NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    request_sha VARCHAR(64) NOT NULL,
    result_set_id VARCHAR(36),
    result_sha VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    retention_ref VARCHAR(500) NOT NULL,
    retention_version BIGINT NOT NULL,
    retention_evidence_sha VARCHAR(64) NOT NULL,
    CONSTRAINT fk_con3_inv_task FOREIGN KEY (task_id) REFERENCES con3_task(task_id),
    CONSTRAINT uq_con3_inv_request UNIQUE (task_id, actor_subject, request_id),
    CONSTRAINT ck_con3_inv_kind CHECK (kind IN ('PROPOSAL','EXPLANATION')),
    CONSTRAINT ck_con3_inv_version CHECK (task_version > 0 AND retention_version > 0),
    CONSTRAINT ck_con3_inv_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_con3_inv_result CHECK (
        (kind='PROPOSAL' AND result_set_id IS NULL AND result_sha IS NULL) OR
        (kind='EXPLANATION' AND result_set_id IS NOT NULL AND result_sha IS NOT NULL))
);
