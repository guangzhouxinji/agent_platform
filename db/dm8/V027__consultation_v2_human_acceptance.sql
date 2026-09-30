CREATE TABLE con2_human_acceptance (
    task_id VARCHAR(36) NOT NULL,
    request_seq BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    receipt_id VARCHAR(36) NOT NULL,
    accepted_by VARCHAR(200) NOT NULL,
    org_ref VARCHAR(200) NOT NULL,
    auth_version BIGINT NOT NULL,
    accepted_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_human_acceptance PRIMARY KEY (task_id, request_seq),
    CONSTRAINT uq_con2_human_acceptance_request UNIQUE (request_id),
    CONSTRAINT uq_con2_human_acceptance_receipt UNIQUE (receipt_id),
    CONSTRAINT fk_con2_human_acceptance_request FOREIGN KEY (task_id, request_seq)
        REFERENCES con2_human_request(task_id, request_seq),
    CONSTRAINT fk_con2_human_acceptance_receipt FOREIGN KEY (receipt_id)
        REFERENCES con2_receipt(receipt_id)
);
