CREATE TABLE con2_result_state (
    task_id VARCHAR(36) NOT NULL,
    result_seq BIGINT NOT NULL,
    focus_seq BIGINT NOT NULL,
    CONSTRAINT pk_con2_result_state PRIMARY KEY (task_id),
    CONSTRAINT fk_con2_result_state_task FOREIGN KEY (task_id) REFERENCES con2_task(task_id),
    CONSTRAINT ck_con2_result_state_seq CHECK (result_seq >= 0 AND focus_seq >= 0)
);

CREATE TABLE con2_result_set (
    set_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    seq BIGINT NOT NULL,
    input_revision BIGINT NOT NULL,
    method_version VARCHAR(32) NOT NULL,
    actual_scope INTEGER NOT NULL,
    receipt_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_result_set PRIMARY KEY (set_id),
    CONSTRAINT uq_con2_result_set_seq UNIQUE (task_id, seq),
    CONSTRAINT uq_con2_result_set_task UNIQUE (task_id, set_id),
    CONSTRAINT fk_con2_result_set_task FOREIGN KEY (task_id) REFERENCES con2_task(task_id),
    CONSTRAINT fk_con2_result_set_receipt FOREIGN KEY (receipt_id) REFERENCES con2_receipt(receipt_id)
);

CREATE TABLE con2_result_member (
    set_id VARCHAR(36) NOT NULL,
    ordinal INTEGER NOT NULL,
    record_id VARCHAR(100) NOT NULL,
    source_version VARCHAR(32) NOT NULL,
    content_sha CHAR(64) NOT NULL,
    locator VARCHAR(100) NOT NULL,
    CONSTRAINT pk_con2_result_member PRIMARY KEY (set_id, ordinal),
    CONSTRAINT fk_con2_result_member_set FOREIGN KEY (set_id) REFERENCES con2_result_set(set_id),
    CONSTRAINT ck_con2_result_member_ordinal CHECK (ordinal > 0)
);

CREATE TABLE con2_focus (
    task_id VARCHAR(36) NOT NULL,
    seq BIGINT NOT NULL,
    set_id VARCHAR(36) NOT NULL,
    ordinal INTEGER NOT NULL,
    input_revision BIGINT NOT NULL,
    receipt_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_focus PRIMARY KEY (task_id, seq),
    CONSTRAINT fk_con2_focus_member FOREIGN KEY (set_id, ordinal)
        REFERENCES con2_result_member(set_id, ordinal),
    CONSTRAINT fk_con2_focus_task_set FOREIGN KEY (task_id, set_id)
        REFERENCES con2_result_set(task_id, set_id),
    CONSTRAINT fk_con2_focus_receipt FOREIGN KEY (receipt_id) REFERENCES con2_receipt(receipt_id)
);
