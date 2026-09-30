ALTER TABLE enf8_job_pack_revision DROP CONSTRAINT ck_enf8_pack_state;
ALTER TABLE enf8_job_pack_revision ADD CONSTRAINT ck_enf8_pack_state CHECK(state IN (
    'COLLECTING','WAITING_DATA','WAITING_REAL_HUMAN_ACTION','READY','RUNNING','PASSED','FAILED'));
CREATE TABLE enf8_real_business_task (
    task_id VARCHAR(120) PRIMARY KEY, candidate_id VARCHAR(160) NOT NULL,
    package_id VARCHAR(120) NOT NULL UNIQUE, title VARCHAR(500) NOT NULL,
    collection_batch_id VARCHAR(120) NOT NULL, public_payload CLOB NOT NULL,
    assignment_state VARCHAR(16) NOT NULL, workflow_state VARCHAR(32) NOT NULL,
    claimed_by VARCHAR(200), organization_id VARCHAR(200), claimed_at TIMESTAMP,
    version BIGINT NOT NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_enf8_real_candidate UNIQUE(candidate_id),
    CONSTRAINT ck_enf8_real_assignment CHECK(assignment_state IN ('UNCLAIMED','CLAIMED')),
    CONSTRAINT ck_enf8_real_workflow CHECK(workflow_state IN ('WAITING_REAL_HUMAN_ACTION','READY','RUNNING','PASSED','FAILED')),
    CONSTRAINT ck_enf8_real_claim CHECK(
        (assignment_state='UNCLAIMED' AND claimed_by IS NULL AND organization_id IS NULL AND claimed_at IS NULL)
        OR (assignment_state='CLAIMED' AND claimed_by IS NOT NULL AND organization_id IS NOT NULL AND claimed_at IS NOT NULL)),
    CONSTRAINT ck_enf8_real_version CHECK(version >= 1)
);
CREATE TABLE enf8_real_business_task_event (
    request_key VARCHAR(120) PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(24) NOT NULL,
    task_id VARCHAR(120) NOT NULL, task_version BIGINT NOT NULL, occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enf8_real_event_task FOREIGN KEY(task_id) REFERENCES enf8_real_business_task(task_id),
    CONSTRAINT ck_enf8_real_event_operation CHECK(operation IN ('CLAIM')),
    CONSTRAINT ck_enf8_real_event_version CHECK(task_version >= 1)
);
