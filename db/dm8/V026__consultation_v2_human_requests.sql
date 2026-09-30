CREATE TABLE con2_human_request_state (
    task_id VARCHAR(36) NOT NULL,
    request_seq BIGINT NOT NULL,
    CONSTRAINT pk_con2_human_request_state PRIMARY KEY (task_id),
    CONSTRAINT fk_con2_human_request_state_task FOREIGN KEY (task_id) REFERENCES con2_task(task_id),
    CONSTRAINT ck_con2_human_request_state_seq CHECK (request_seq >= 0)
);

CREATE TABLE con2_human_request (
    task_id VARCHAR(36) NOT NULL,
    request_seq BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    input_revision BIGINT NOT NULL,
    cancel_epoch BIGINT NOT NULL,
    actor_ref VARCHAR(200) NOT NULL,
    org_ref VARCHAR(200) NOT NULL,
    auth_version BIGINT NOT NULL,
    manifest_id VARCHAR(100) NOT NULL,
    manifest_sha CHAR(64) NOT NULL,
    text_sha CHAR(64) NOT NULL,
    withdrawal_version BIGINT NOT NULL,
    receipt_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_human_request PRIMARY KEY (task_id, request_seq),
    CONSTRAINT uq_con2_human_request_id UNIQUE (request_id),
    CONSTRAINT uq_con2_human_request_receipt UNIQUE (receipt_id),
    CONSTRAINT fk_con2_human_request_state FOREIGN KEY (task_id)
        REFERENCES con2_human_request_state(task_id),
    CONSTRAINT fk_con2_human_request_input FOREIGN KEY (task_id, input_revision)
        REFERENCES con2_message(task_id, revision),
    CONSTRAINT fk_con2_human_request_receipt FOREIGN KEY (receipt_id) REFERENCES con2_receipt(receipt_id),
    CONSTRAINT ck_con2_human_request_seq CHECK (request_seq > 0)
);
