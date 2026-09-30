CREATE TABLE con2_purpose_state (
    task_id VARCHAR(36) NOT NULL,
    purpose_seq BIGINT NOT NULL,
    CONSTRAINT pk_con2_purpose_state PRIMARY KEY (task_id),
    CONSTRAINT fk_con2_purpose_state_task FOREIGN KEY (task_id) REFERENCES con2_task(task_id),
    CONSTRAINT ck_con2_purpose_state_seq CHECK (purpose_seq >= 0)
);

CREATE TABLE con2_purpose_revision (
    task_id VARCHAR(36) NOT NULL,
    purpose_seq BIGINT NOT NULL,
    input_revision BIGINT NOT NULL,
    feature_seq BIGINT NOT NULL,
    focus_seq BIGINT NOT NULL,
    set_id VARCHAR(36) NOT NULL,
    ordinal INTEGER NOT NULL,
    purpose_code VARCHAR(32) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL,
    org_ref VARCHAR(200) NOT NULL,
    auth_version BIGINT NOT NULL,
    receipt_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_purpose_revision PRIMARY KEY (task_id, purpose_seq),
    CONSTRAINT uq_con2_purpose_input UNIQUE (task_id, input_revision, feature_seq, focus_seq),
    CONSTRAINT uq_con2_purpose_receipt UNIQUE (receipt_id),
    CONSTRAINT fk_con2_purpose_state FOREIGN KEY (task_id) REFERENCES con2_purpose_state(task_id),
    CONSTRAINT fk_con2_purpose_input FOREIGN KEY (task_id, input_revision)
        REFERENCES con2_message(task_id, revision),
    CONSTRAINT fk_con2_purpose_feature FOREIGN KEY (task_id, feature_seq)
        REFERENCES con2_feature_revision(task_id, feature_seq),
    CONSTRAINT fk_con2_purpose_focus FOREIGN KEY (task_id, focus_seq)
        REFERENCES con2_focus(task_id, seq),
    CONSTRAINT fk_con2_purpose_member FOREIGN KEY (set_id, ordinal)
        REFERENCES con2_result_member(set_id, ordinal),
    CONSTRAINT fk_con2_purpose_receipt FOREIGN KEY (receipt_id) REFERENCES con2_receipt(receipt_id),
    CONSTRAINT ck_con2_purpose_seq CHECK (purpose_seq > 0),
    CONSTRAINT ck_con2_purpose_code CHECK (purpose_code = 'TECHNICAL_SIMILARITY')
);
