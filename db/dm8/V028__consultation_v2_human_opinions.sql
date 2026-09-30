CREATE TABLE con2_human_opinion (
    task_id VARCHAR(36) NOT NULL,
    request_seq BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    opinion_id VARCHAR(36) NOT NULL,
    receipt_id VARCHAR(36) NOT NULL,
    opinion_input_id VARCHAR(100) NOT NULL,
    input_revision BIGINT NOT NULL,
    cancel_epoch BIGINT NOT NULL,
    actor_ref VARCHAR(200) NOT NULL,
    org_ref VARCHAR(200) NOT NULL,
    auth_version BIGINT NOT NULL,
    manifest_id VARCHAR(100) NOT NULL,
    manifest_sha CHAR(64) NOT NULL,
    text_sha CHAR(64) NOT NULL,
    withdrawal_version BIGINT NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_human_opinion PRIMARY KEY (task_id, request_seq),
    CONSTRAINT uq_con2_human_opinion_id UNIQUE (opinion_id),
    CONSTRAINT uq_con2_human_opinion_receipt UNIQUE (receipt_id),
    CONSTRAINT fk_con2_human_opinion_accept FOREIGN KEY (task_id, request_seq)
        REFERENCES con2_human_acceptance(task_id, request_seq),
    CONSTRAINT fk_con2_human_opinion_receipt FOREIGN KEY (receipt_id)
        REFERENCES con2_receipt(receipt_id)
);
