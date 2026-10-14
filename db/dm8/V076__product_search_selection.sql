CREATE TABLE con4_selection_revision (
    selection_id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    task_version BIGINT NOT NULL,
    result_set_id VARCHAR(36) NOT NULL,
    result_set_version BIGINT NOT NULL,
    result_sha VARCHAR(64) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    request_id VARCHAR(200) NOT NULL,
    request_sha VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_con4_selection_revision UNIQUE (task_id, revision),
    CONSTRAINT uq_con4_selection_request UNIQUE (task_id, actor_subject, request_id),
    CONSTRAINT uq_con4_selection_set UNIQUE (selection_id, result_set_id),
    CONSTRAINT fk_con4_selection_task FOREIGN KEY (task_id) REFERENCES con3_task(task_id),
    CONSTRAINT fk_con4_selection_result FOREIGN KEY (result_set_id) REFERENCES con4_result_set(result_set_id),
    CONSTRAINT ck_con4_selection_version CHECK (revision>0 AND task_version>0 AND result_set_version=1),
    CONSTRAINT ck_con4_selection_expiry CHECK (expires_at>created_at)
);
CREATE TABLE con4_selection_member (
    selection_id VARCHAR(36) NOT NULL,
    result_set_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(36) NOT NULL,
    ordinal BIGINT NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    document_id VARCHAR(300) NOT NULL,
    document_version VARCHAR(200) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    raw_sha VARCHAR(64) NOT NULL,
    locator_ref VARCHAR(1000) NOT NULL,
    PRIMARY KEY (selection_id, member_id),
    CONSTRAINT fk_con4_selection_member_parent FOREIGN KEY (selection_id, result_set_id)
        REFERENCES con4_selection_revision(selection_id, result_set_id),
    CONSTRAINT fk_con4_selection_member_result FOREIGN KEY (result_set_id, member_id)
        REFERENCES con4_result_member(result_set_id, member_id),
    CONSTRAINT ck_con4_selection_ordinal CHECK (ordinal>=0)
);
