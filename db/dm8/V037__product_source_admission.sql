CREATE TABLE con4_source_admission (
    decision_id VARCHAR(36) NOT NULL PRIMARY KEY,
    source_id VARCHAR(200) NOT NULL,
    contract_version VARCHAR(100) NOT NULL,
    rights_ref VARCHAR(500) NOT NULL,
    rights_sha VARCHAR(64) NOT NULL,
    capability_ref VARCHAR(500) NOT NULL,
    capability_sha VARCHAR(64) NOT NULL,
    responsible_party VARCHAR(300) NOT NULL,
    maintainer_ref VARCHAR(300) NOT NULL,
    coverage_sha VARCHAR(64) NOT NULL,
    valid_until TIMESTAMP NOT NULL,
    decided_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_con4_admission_contract UNIQUE (source_id, contract_version),
    CONSTRAINT uq_con4_admission_key UNIQUE (decision_id, source_id, contract_version),
    CONSTRAINT fk_con4_admission_contract FOREIGN KEY (source_id, contract_version)
        REFERENCES con4_source_contract(source_id, contract_version),
    CONSTRAINT ck_con4_admission_valid CHECK (valid_until > decided_at)
);

CREATE TABLE con4_source_field_use (
    decision_id VARCHAR(36) NOT NULL,
    field_code VARCHAR(100) NOT NULL,
    use_code VARCHAR(16) NOT NULL,
    valid_until TIMESTAMP NOT NULL,
    PRIMARY KEY (decision_id, field_code, use_code),
    CONSTRAINT fk_con4_field_admission FOREIGN KEY (decision_id)
        REFERENCES con4_source_admission(decision_id),
    CONSTRAINT ck_con4_field_use CHECK (use_code IN
        ('QUERY','SAVE','INDEX','DISPLAY','DOWNLOAD','MODEL','SHARE'))
);

CREATE TABLE con4_source_current (
    source_id VARCHAR(200) NOT NULL PRIMARY KEY,
    contract_version VARCHAR(100) NOT NULL,
    decision_id VARCHAR(36) NOT NULL,
    source_epoch BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    row_version BIGINT NOT NULL,
    history_sha VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con4_current_contract FOREIGN KEY (source_id, contract_version)
        REFERENCES con4_source_contract(source_id, contract_version),
    CONSTRAINT fk_con4_current_admission FOREIGN KEY (decision_id, source_id, contract_version)
        REFERENCES con4_source_admission(decision_id, source_id, contract_version),
    CONSTRAINT ck_con4_current_status CHECK (status IN ('ACTIVE','WITHDRAWN')),
    CONSTRAINT ck_con4_current_version CHECK (source_epoch > 0 AND row_version > 0)
);

CREATE TABLE con4_source_authority_event (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    source_id VARCHAR(200) NOT NULL,
    contract_version VARCHAR(100) NOT NULL,
    decision_id VARCHAR(36) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    before_epoch BIGINT NOT NULL,
    after_epoch BIGINT NOT NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    intent_id VARCHAR(36) NOT NULL,
    before_history_sha VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(300) NOT NULL,
    request_sha VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con4_authority_decision FOREIGN KEY (decision_id, source_id, contract_version)
        REFERENCES con4_source_admission(decision_id, source_id, contract_version),
    CONSTRAINT ck_con4_authority_operation CHECK (operation IN ('ADMIT','WITHDRAW','EXPIRE')),
    CONSTRAINT ck_con4_authority_epoch CHECK (after_epoch > before_epoch AND before_epoch >= 0),
    CONSTRAINT ck_con4_authority_version CHECK (after_version > before_version AND before_version >= 0)
);

CREATE TABLE con4_source_authority_request (
    request_id VARCHAR(200) NOT NULL PRIMARY KEY,
    request_sha VARCHAR(64) NOT NULL,
    event_id VARCHAR(36) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con4_authority_request_event FOREIGN KEY (event_id)
        REFERENCES con4_source_authority_event(event_id)
);

CREATE INDEX ix_con4_authority_source ON con4_source_authority_event(source_id, after_epoch);
CREATE INDEX ix_con4_field_use ON con4_source_field_use(field_code, use_code);
