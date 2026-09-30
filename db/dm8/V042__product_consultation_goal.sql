ALTER TABLE con3_task DROP CONSTRAINT ck_con3_task_state;
ALTER TABLE con3_task ADD CONSTRAINT ck_con3_task_state
    CHECK (state IN ('AWAITING_GOAL','IN_PROGRESS','USER_CANCELLED'));
ALTER TABLE con3_event DROP CONSTRAINT ck_con3_event_operation;
ALTER TABLE con3_event ADD CONSTRAINT ck_con3_event_operation
    CHECK (operation IN ('INITIAL_CREATE','MESSAGE_ACCEPTED','GOAL_CONFIRMED','USER_CANCELLED'));

CREATE TABLE con3_session (
    session_id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_con3_session_task UNIQUE (task_id,session_id),
    CONSTRAINT fk_con3_session_task FOREIGN KEY (task_id) REFERENCES con3_task(task_id),
    CONSTRAINT ck_con3_session_status CHECK (status IN ('ACTIVE','ENDED','EXPIRED')),
    CONSTRAINT ck_con3_session_expiry CHECK (expires_at > created_at)
);

CREATE TABLE con3_message (
    message_id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    sequence_no BIGINT NOT NULL,
    actor_issuer VARCHAR(300) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    raw_text CLOB NOT NULL,
    content_sha VARCHAR(64) NOT NULL,
    received_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    retention_ref VARCHAR(200) NOT NULL,
    retention_version BIGINT NOT NULL,
    retention_evidence_sha VARCHAR(64) NOT NULL,
    CONSTRAINT uq_con3_message_task UNIQUE (task_id,message_id),
    CONSTRAINT uq_con3_message_sequence UNIQUE (session_id,sequence_no),
    CONSTRAINT fk_con3_message_session FOREIGN KEY (task_id,session_id)
        REFERENCES con3_session(task_id,session_id),
    CONSTRAINT fk_con3_message_actor FOREIGN KEY (actor_issuer,actor_subject)
        REFERENCES id2_subject(issuer,subject_id),
    CONSTRAINT ck_con3_message_values CHECK (sequence_no > 0 AND retention_version > 0
        AND expires_at > received_at)
);

CREATE TABLE con3_goal_revision (
    goal_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(200) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    object_text CLOB NOT NULL,
    scope_json CLOB NOT NULL,
    features_json CLOB NOT NULL,
    source_message_id VARCHAR(36) NOT NULL,
    confirmed_issuer VARCHAR(300) NOT NULL,
    confirmed_subject VARCHAR(200) NOT NULL,
    confirmed_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    content_sha VARCHAR(64) NOT NULL,
    retention_ref VARCHAR(200) NOT NULL,
    retention_version BIGINT NOT NULL,
    retention_evidence_sha VARCHAR(64) NOT NULL,
    CONSTRAINT pk_con3_goal_revision PRIMARY KEY (goal_id,revision),
    CONSTRAINT uq_con3_goal_task UNIQUE (task_id,goal_id,revision),
    CONSTRAINT fk_con3_goal_task FOREIGN KEY (task_id,organization_id)
        REFERENCES con3_task(task_id,organization_id),
    CONSTRAINT fk_con3_goal_message FOREIGN KEY (task_id,source_message_id)
        REFERENCES con3_message(task_id,message_id),
    CONSTRAINT fk_con3_goal_actor FOREIGN KEY (confirmed_issuer,confirmed_subject)
        REFERENCES id2_subject(issuer,subject_id),
    CONSTRAINT ck_con3_goal_values CHECK (revision > 0 AND retention_version > 0
        AND expires_at > confirmed_at),
    CONSTRAINT ck_con3_goal_purpose CHECK (purpose IN ('QUERY','SEARCH','COMPARE')),
    CONSTRAINT ck_con3_goal_status CHECK (status = 'CONFIRMED')
);

CREATE TABLE con3_goal_current (
    task_id VARCHAR(36) NOT NULL PRIMARY KEY,
    goal_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    task_version BIGINT NOT NULL,
    CONSTRAINT fk_con3_current_goal FOREIGN KEY (task_id,goal_id,revision)
        REFERENCES con3_goal_revision(task_id,goal_id,revision),
    CONSTRAINT ck_con3_current_version CHECK (task_version > 0)
);

CREATE TABLE con3_search_input (
    input_id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    goal_id VARCHAR(36) NOT NULL,
    goal_revision BIGINT NOT NULL,
    task_version BIGINT NOT NULL,
    query_text CLOB NOT NULL,
    source_scope_json CLOB NOT NULL,
    requested_fields_json CLOB NOT NULL,
    content_sha VARCHAR(64) NOT NULL,
    confirmed_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_con3_input_goal UNIQUE (task_id,goal_id,goal_revision),
    CONSTRAINT fk_con3_input_goal FOREIGN KEY (task_id,goal_id,goal_revision)
        REFERENCES con3_goal_revision(task_id,goal_id,revision),
    CONSTRAINT ck_con3_input_values CHECK (task_version > 0 AND expires_at > confirmed_at)
);

CREATE TABLE con3_command_receipt (
    task_id VARCHAR(36) NOT NULL,
    actor_issuer VARCHAR(300) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    request_id VARCHAR(200) NOT NULL,
    request_sha VARCHAR(64) NOT NULL,
    result_id VARCHAR(36) NOT NULL,
    result_version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con3_command_receipt PRIMARY KEY
        (task_id,actor_issuer,actor_subject,operation,request_id),
    CONSTRAINT fk_con3_command_task FOREIGN KEY (task_id) REFERENCES con3_task(task_id),
    CONSTRAINT fk_con3_command_actor FOREIGN KEY (actor_issuer,actor_subject)
        REFERENCES id2_subject(issuer,subject_id),
    CONSTRAINT ck_con3_command_operation CHECK (operation IN ('MESSAGE_ACCEPTED','GOAL_CONFIRMED','USER_CANCELLED')),
    CONSTRAINT ck_con3_command_version CHECK (result_version > 0)
);
