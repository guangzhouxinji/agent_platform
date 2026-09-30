CREATE TABLE con2_human_return (
    task_id VARCHAR(36) NOT NULL,
    request_seq BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    return_id VARCHAR(36) NOT NULL,
    receipt_id VARCHAR(36) NOT NULL,
    input_revision BIGINT NOT NULL,
    cancel_epoch BIGINT NOT NULL,
    actor_ref VARCHAR(200) NOT NULL,
    org_ref VARCHAR(200) NOT NULL,
    auth_version BIGINT NOT NULL,
    returned_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_human_return PRIMARY KEY (task_id, request_seq),
    CONSTRAINT uq_con2_human_return_id UNIQUE (return_id),
    CONSTRAINT uq_con2_human_return_receipt UNIQUE (receipt_id),
    CONSTRAINT fk_con2_human_return_accept FOREIGN KEY (task_id, request_seq)
        REFERENCES con2_human_acceptance(task_id, request_seq),
    CONSTRAINT fk_con2_human_return_receipt FOREIGN KEY (receipt_id)
        REFERENCES con2_receipt(receipt_id)
);
