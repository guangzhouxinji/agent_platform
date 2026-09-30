CREATE TABLE con2_correction (
    task_id VARCHAR(36) NOT NULL,
    new_revision BIGINT NOT NULL,
    old_revision BIGINT NOT NULL,
    actor_ref VARCHAR(200) NOT NULL,
    org_ref VARCHAR(200) NOT NULL,
    auth_version BIGINT NOT NULL,
    manifest_id VARCHAR(100) NOT NULL,
    manifest_sha CHAR(64) NOT NULL,
    old_sha CHAR(64) NOT NULL,
    new_sha CHAR(64) NOT NULL,
    utterance_sha CHAR(64) NOT NULL,
    utterance CLOB NOT NULL,
    receipt_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con2_correction PRIMARY KEY (task_id, new_revision),
    CONSTRAINT uq_con2_correction_receipt UNIQUE (receipt_id),
    CONSTRAINT fk_con2_correction_old FOREIGN KEY (task_id, old_revision)
        REFERENCES con2_message(task_id, revision),
    CONSTRAINT fk_con2_correction_new FOREIGN KEY (task_id, new_revision)
        REFERENCES con2_message(task_id, revision),
    CONSTRAINT fk_con2_correction_receipt FOREIGN KEY (receipt_id)
        REFERENCES con2_receipt(receipt_id),
    CONSTRAINT ck_con2_correction_revision CHECK (old_revision >= 2 AND new_revision = old_revision + 1)
);
