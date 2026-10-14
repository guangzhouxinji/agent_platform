ALTER TABLE con3_search_input ADD CONSTRAINT uq_con3_input_task_version
    UNIQUE (task_id,input_id,task_version);
CREATE TABLE con3_execution_attempt (
    attempt_id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    input_id VARCHAR(36) NOT NULL,
    task_version BIGINT NOT NULL,
    cancel_epoch BIGINT NOT NULL,
    fencing_token BIGINT NOT NULL,
    worker_ref VARCHAR(200) NOT NULL,
    lease_until TIMESTAMP NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_con3_attempt_task_token UNIQUE (task_id,fencing_token),
    CONSTRAINT uq_con3_attempt_binding UNIQUE (attempt_id,task_id,fencing_token),
    CONSTRAINT fk_con3_attempt_input FOREIGN KEY (task_id,input_id,task_version)
        REFERENCES con3_search_input(task_id,input_id,task_version),
    CONSTRAINT ck_con3_attempt_versions CHECK (task_version>0 AND cancel_epoch>=0 AND fencing_token>0),
    CONSTRAINT ck_con3_attempt_time CHECK (lease_until>issued_at)
);
INSERT INTO con3_execution_attempt(attempt_id,task_id,input_id,task_version,cancel_epoch,
    fencing_token,worker_ref,lease_until,issued_at)
    SELECT attempt_id,task_id,input_id,task_version,cancel_epoch,fencing_token,worker_ref,
        lease_until,updated_at FROM con3_execution_lease;
