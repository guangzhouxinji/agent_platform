CREATE TABLE con2_feature_state (
    task_id VARCHAR(36) NOT NULL,
    feature_seq BIGINT NOT NULL,
    CONSTRAINT pk_con2_feature_state PRIMARY KEY (task_id),
    CONSTRAINT fk_con2_feature_state_task FOREIGN KEY (task_id) REFERENCES con2_task(task_id),
    CONSTRAINT ck_con2_feature_state_seq CHECK (feature_seq >= 0)
);

CREATE TABLE con2_feature_revision (
    task_id VARCHAR(36) NOT NULL,
    feature_seq BIGINT NOT NULL,
    input_revision BIGINT NOT NULL,
    actor_ref VARCHAR(200) NOT NULL,
    org_ref VARCHAR(200) NOT NULL,
    auth_version BIGINT NOT NULL,
    manifest_id VARCHAR(100) NOT NULL,
    manifest_sha CHAR(64) NOT NULL,
    input_sha CHAR(64) NOT NULL,
    withdrawal_version BIGINT NOT NULL,
    receipt_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_feature_revision PRIMARY KEY (task_id, feature_seq),
    CONSTRAINT uq_con2_feature_receipt UNIQUE (receipt_id),
    CONSTRAINT fk_con2_feature_state FOREIGN KEY (task_id) REFERENCES con2_feature_state(task_id),
    CONSTRAINT fk_con2_feature_input FOREIGN KEY (task_id, input_revision)
        REFERENCES con2_message(task_id, revision),
    CONSTRAINT fk_con2_feature_receipt FOREIGN KEY (receipt_id) REFERENCES con2_receipt(receipt_id),
    CONSTRAINT ck_con2_feature_revision_seq CHECK (feature_seq > 0)
);

CREATE TABLE con2_feature_member (
    task_id VARCHAR(36) NOT NULL,
    feature_seq BIGINT NOT NULL,
    feature_id VARCHAR(10) NOT NULL,
    candidate_sha CHAR(64) NOT NULL,
    byte_start INTEGER NOT NULL,
    byte_end INTEGER NOT NULL,
    CONSTRAINT pk_con2_feature_member PRIMARY KEY (task_id, feature_seq, feature_id),
    CONSTRAINT fk_con2_feature_member_revision FOREIGN KEY (task_id, feature_seq)
        REFERENCES con2_feature_revision(task_id, feature_seq),
    CONSTRAINT ck_con2_feature_span CHECK (byte_start >= 0 AND byte_end > byte_start)
);
