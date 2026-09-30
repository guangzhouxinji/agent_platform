CREATE TABLE enf3_source (
    source_id VARCHAR(120) PRIMARY KEY, organization_id VARCHAR(200) NOT NULL,
    rights_ref VARCHAR(300) NOT NULL, source_epoch BIGINT NOT NULL,
    expires_at TIMESTAMP NOT NULL, allowed_uses VARCHAR(300) NOT NULL,
    active CHAR(1) NOT NULL, credential_ref VARCHAR(120) NOT NULL,
    config_version BIGINT NOT NULL,
    CONSTRAINT ck_enf3_source_epoch CHECK (source_epoch > 0),
    CONSTRAINT ck_enf3_source_config_version CHECK (config_version > 0),
    CONSTRAINT ck_enf3_source_active CHECK (active IN ('Y','N'))
);

CREATE TABLE enf3_source_config_revision (
    source_id VARCHAR(120) NOT NULL, config_version BIGINT NOT NULL,
    credential_ref VARCHAR(120) NOT NULL, active CHAR(1) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL, changed_at TIMESTAMP NOT NULL,
    PRIMARY KEY(source_id,config_version),
    CONSTRAINT fk_enf3_config_source FOREIGN KEY (source_id) REFERENCES enf3_source(source_id),
    CONSTRAINT ck_enf3_config_active CHECK (active IN ('Y','N'))
);

CREATE TABLE enf3_sync_cursor (
    source_id VARCHAR(120) PRIMARY KEY, cursor_value VARCHAR(200) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enf3_cursor_source FOREIGN KEY (source_id) REFERENCES enf3_source(source_id)
);

CREATE TABLE enf3_sync_attempt (
    attempt_id VARCHAR(36) PRIMARY KEY, source_id VARCHAR(120) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL, start_cursor VARCHAR(200) NOT NULL,
    config_version BIGINT NOT NULL,
    end_cursor VARCHAR(200), processed_count INTEGER NOT NULL,
    state VARCHAR(24) NOT NULL, failure_code VARCHAR(80), failure_cursor VARCHAR(200),
    started_at TIMESTAMP NOT NULL, finished_at TIMESTAMP,
    CONSTRAINT fk_enf3_attempt_source FOREIGN KEY (source_id) REFERENCES enf3_source(source_id),
    CONSTRAINT ck_enf3_attempt_state CHECK (state IN ('RUNNING','CONTINUE','SUCCEEDED',
        'PARTIAL_FAILED','FAILED'))
);

CREATE TABLE enf3_sync_record (
    attempt_id VARCHAR(36) NOT NULL, ordinal_no INTEGER NOT NULL,
    record_id VARCHAR(120), source_version VARCHAR(120),
    before_cursor VARCHAR(200) NOT NULL, next_cursor VARCHAR(200),
    state VARCHAR(16) NOT NULL, failure_code VARCHAR(80),
    PRIMARY KEY(attempt_id,ordinal_no),
    CONSTRAINT fk_enf3_sync_record_attempt FOREIGN KEY (attempt_id) REFERENCES enf3_sync_attempt(attempt_id),
    CONSTRAINT ck_enf3_sync_record_state CHECK (state IN ('COMMITTED','FAILED'))
);

CREATE TABLE enf3_observation (
    observation_id VARCHAR(64) PRIMARY KEY, source_id VARCHAR(120) NOT NULL,
    record_id VARCHAR(120) NOT NULL, source_version VARCHAR(120) NOT NULL,
    organization_id VARCHAR(200) NOT NULL, source_text CLOB NOT NULL,
    text_sha256 VARCHAR(64) NOT NULL, material_mention VARCHAR(300),
    revision BIGINT NOT NULL,
    CONSTRAINT uq_enf3_observation_source UNIQUE (source_id,record_id,source_version),
    CONSTRAINT fk_enf3_observation_source FOREIGN KEY (source_id) REFERENCES enf3_source(source_id)
);

CREATE TABLE enf3_rule (
    rule_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    organization_id VARCHAR(200) NOT NULL, source_id VARCHAR(120) NOT NULL,
    literal_value VARCHAR(300) NOT NULL, basis_ref VARCHAR(300) NOT NULL,
    state VARCHAR(16) NOT NULL, publisher VARCHAR(200),
    PRIMARY KEY(rule_id,revision),
    CONSTRAINT fk_enf3_rule_source FOREIGN KEY (source_id) REFERENCES enf3_source(source_id),
    CONSTRAINT ck_enf3_rule_state CHECK (state IN ('DRAFT','ACTIVE','DISABLED'))
);

CREATE TABLE enf3_actor_mention (
    observation_id VARCHAR(64) NOT NULL, ordinal_no INTEGER NOT NULL,
    role_code VARCHAR(80) NOT NULL, literal_value VARCHAR(300) NOT NULL,
    source_locator VARCHAR(400) NOT NULL, business_code VARCHAR(120),
    PRIMARY KEY(observation_id,ordinal_no),
    CONSTRAINT fk_enf3_actor_observation FOREIGN KEY (observation_id) REFERENCES enf3_observation(observation_id)
);

CREATE TABLE enf3_clue (
    clue_id VARCHAR(64) PRIMARY KEY, observation_id VARCHAR(64) NOT NULL UNIQUE,
    rule_id VARCHAR(120) NOT NULL, rule_revision BIGINT NOT NULL,
    organization_id VARCHAR(200) NOT NULL, state VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL,
    CONSTRAINT fk_enf3_clue_observation FOREIGN KEY (observation_id) REFERENCES enf3_observation(observation_id),
    CONSTRAINT fk_enf3_clue_rule FOREIGN KEY (rule_id,rule_revision) REFERENCES enf3_rule(rule_id,revision)
);

CREATE TABLE enf3_rule_review (
    rule_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    positive_observation_id VARCHAR(64) NOT NULL, negative_observation_id VARCHAR(64) NOT NULL,
    positive_sha256 VARCHAR(64) NOT NULL, negative_sha256 VARCHAR(64) NOT NULL,
    reviewer VARCHAR(200) NOT NULL, reviewed_at TIMESTAMP NOT NULL,
    PRIMARY KEY(rule_id,revision),
    CONSTRAINT fk_enf3_review_rule FOREIGN KEY (rule_id,revision) REFERENCES enf3_rule(rule_id,revision),
    CONSTRAINT fk_enf3_review_positive FOREIGN KEY (positive_observation_id) REFERENCES enf3_observation(observation_id),
    CONSTRAINT fk_enf3_review_negative FOREIGN KEY (negative_observation_id) REFERENCES enf3_observation(observation_id),
    CONSTRAINT ck_enf3_review_distinct CHECK (positive_observation_id <> negative_observation_id)
);

CREATE TABLE enf3_revision (
    clue_id VARCHAR(64) NOT NULL, revision BIGINT NOT NULL,
    actor_ref VARCHAR(200) NOT NULL, reason VARCHAR(500) NOT NULL,
    original_text CLOB NOT NULL, corrected_text CLOB NOT NULL,
    material_gap VARCHAR(80) NOT NULL,
    PRIMARY KEY(clue_id,revision),
    CONSTRAINT fk_enf3_revision_clue FOREIGN KEY (clue_id) REFERENCES enf3_clue(clue_id)
);

CREATE TABLE enf3_clue_rule (
    clue_id VARCHAR(64) NOT NULL, rule_id VARCHAR(120) NOT NULL, rule_revision BIGINT NOT NULL,
    PRIMARY KEY(clue_id,rule_id,rule_revision),
    CONSTRAINT fk_enf3_clue_rule_clue FOREIGN KEY (clue_id) REFERENCES enf3_clue(clue_id),
    CONSTRAINT fk_enf3_clue_rule_rule FOREIGN KEY (rule_id,rule_revision) REFERENCES enf3_rule(rule_id,revision)
);

CREATE TABLE enf3_material (
    material_id VARCHAR(36) PRIMARY KEY, clue_id VARCHAR(64) NOT NULL,
    organization_id VARCHAR(200) NOT NULL, sha256 VARCHAR(64) NOT NULL,
    file_key VARCHAR(36) NOT NULL UNIQUE, rights_ref VARCHAR(300) NOT NULL,
    expires_at TIMESTAMP NOT NULL, allowed_uses VARCHAR(300) NOT NULL, collector VARCHAR(200) NOT NULL,
    collected_at VARCHAR(80) NOT NULL, state VARCHAR(32) NOT NULL, revision BIGINT NOT NULL,
    CONSTRAINT fk_enf3_material_clue FOREIGN KEY (clue_id) REFERENCES enf3_clue(clue_id),
    CONSTRAINT ck_enf3_material_state CHECK (state IN ('PENDING_VERIFICATION','VERIFIED','EXCLUDED'))
);

CREATE TABLE enf3_material_action (
    material_id VARCHAR(36) NOT NULL, revision BIGINT NOT NULL,
    action_code VARCHAR(24) NOT NULL, actor_ref VARCHAR(200) NOT NULL,
    source_ref VARCHAR(300), reason VARCHAR(500) NOT NULL,
    file_sha256 VARCHAR(64) NOT NULL, occurred_at TIMESTAMP NOT NULL,
    PRIMARY KEY(material_id,revision),
    CONSTRAINT fk_enf3_action_material FOREIGN KEY (material_id) REFERENCES enf3_material(material_id),
    CONSTRAINT ck_enf3_action_code CHECK (action_code IN ('UPLOAD','COLLECT_ATTEST','VERIFY'))
);

CREATE TABLE enf3_receipt (
    request_id VARCHAR(200) PRIMARY KEY, request_sha VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(40) NOT NULL,
    result_id VARCHAR(120) NOT NULL, result_version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE enf3_receipt_lock (lock_id INTEGER PRIMARY KEY);
INSERT INTO enf3_receipt_lock VALUES(0);
INSERT INTO enf3_receipt_lock VALUES(1);
INSERT INTO enf3_receipt_lock VALUES(2);
INSERT INTO enf3_receipt_lock VALUES(3);
INSERT INTO enf3_receipt_lock VALUES(4);
INSERT INTO enf3_receipt_lock VALUES(5);
INSERT INTO enf3_receipt_lock VALUES(6);
INSERT INTO enf3_receipt_lock VALUES(7);
INSERT INTO enf3_receipt_lock VALUES(8);
INSERT INTO enf3_receipt_lock VALUES(9);
INSERT INTO enf3_receipt_lock VALUES(10);
INSERT INTO enf3_receipt_lock VALUES(11);
INSERT INTO enf3_receipt_lock VALUES(12);
INSERT INTO enf3_receipt_lock VALUES(13);
INSERT INTO enf3_receipt_lock VALUES(14);
INSERT INTO enf3_receipt_lock VALUES(15);

CREATE TABLE enf3_event (
    event_id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(200) NOT NULL,
    operation VARCHAR(40) NOT NULL, actor_ref VARCHAR(200) NOT NULL,
    result_id VARCHAR(120) NOT NULL, occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enf3_event_receipt FOREIGN KEY (request_id) REFERENCES enf3_receipt(request_id)
);

CREATE TABLE enf3_cleanup_event (
    file_key VARCHAR(36) PRIMARY KEY, state VARCHAR(16) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_enf3_cleanup_state CHECK (state IN ('PENDING','FAILED','SUCCEEDED'))
);
