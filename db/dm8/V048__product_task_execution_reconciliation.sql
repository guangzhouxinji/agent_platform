CREATE TABLE con3_execution_dispatch (
    task_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    fencing_token BIGINT NOT NULL,
    operation_id VARCHAR(100) NOT NULL,
    status VARCHAR(24) NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    PRIMARY KEY (task_id,attempt_id,operation_id),
    CONSTRAINT fk_con3_dispatch_task FOREIGN KEY (task_id) REFERENCES con3_task(task_id),
    CONSTRAINT ck_con3_dispatch_token CHECK (fencing_token > 0),
    CONSTRAINT ck_con3_dispatch_status CHECK (status IN ('INTENT','RECONCILED'))
);

CREATE TABLE con3_execution_reconciliation (
    task_id VARCHAR(36) NOT NULL,
    old_attempt_id VARCHAR(36) NOT NULL,
    old_token BIGINT NOT NULL,
    new_attempt_id VARCHAR(36) NOT NULL UNIQUE,
    new_token BIGINT NOT NULL,
    input_id VARCHAR(36) NOT NULL,
    last_checkpoint_sequence BIGINT NOT NULL,
    last_checkpoint_ref VARCHAR(300),
    last_checkpoint_sha CHAR(64),
    actor_ref VARCHAR(200) NOT NULL,
    reconciled_at TIMESTAMP NOT NULL,
    PRIMARY KEY (task_id,old_token),
    CONSTRAINT fk_con3_reconcile_task FOREIGN KEY (task_id) REFERENCES con3_task(task_id),
    CONSTRAINT fk_con3_reconcile_input FOREIGN KEY (task_id,input_id)
        REFERENCES con3_search_input(task_id,input_id),
    CONSTRAINT ck_con3_reconcile_token CHECK (old_token > 0 AND new_token = old_token + 1),
    CONSTRAINT ck_con3_reconcile_checkpoint CHECK (last_checkpoint_sequence >= 0 AND
        ((last_checkpoint_sequence=0 AND last_checkpoint_ref IS NULL AND last_checkpoint_sha IS NULL)
        OR (last_checkpoint_sequence>0 AND last_checkpoint_ref IS NOT NULL
            AND last_checkpoint_sha IS NOT NULL)))
);
