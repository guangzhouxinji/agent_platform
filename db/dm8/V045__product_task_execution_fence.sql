ALTER TABLE con3_search_input ADD CONSTRAINT uq_con3_input_task_id UNIQUE (task_id,input_id);

CREATE TABLE con3_execution_lease (
    task_id VARCHAR(36) NOT NULL PRIMARY KEY,
    attempt_id VARCHAR(36) NOT NULL UNIQUE,
    input_id VARCHAR(36) NOT NULL,
    task_version BIGINT NOT NULL,
    cancel_epoch BIGINT NOT NULL,
    fencing_token BIGINT NOT NULL,
    worker_ref VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL,
    lease_until TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con3_lease_task FOREIGN KEY (task_id) REFERENCES con3_task(task_id),
    CONSTRAINT fk_con3_lease_input FOREIGN KEY (task_id,input_id)
        REFERENCES con3_search_input(task_id,input_id),
    CONSTRAINT ck_con3_lease_versions CHECK (task_version > 0 AND cancel_epoch >= 0 AND fencing_token > 0),
    CONSTRAINT ck_con3_lease_status CHECK (status IN ('ACTIVE','RECONCILE_REQUIRED','COMMITTED','CANCELLED')),
    CONSTRAINT ck_con3_lease_time CHECK (lease_until > updated_at)
);

CREATE TABLE con3_execution_checkpoint (
    task_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    fencing_token BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    result_ref VARCHAR(300) NOT NULL,
    result_sha VARCHAR(64) NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    PRIMARY KEY (task_id,fencing_token,sequence_no),
    CONSTRAINT fk_con3_checkpoint_task FOREIGN KEY (task_id) REFERENCES con3_task(task_id),
    CONSTRAINT ck_con3_checkpoint_values CHECK (fencing_token > 0 AND sequence_no > 0)
);
