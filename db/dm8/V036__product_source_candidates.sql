CREATE TABLE con4_source_contract (
    source_id VARCHAR(200) NOT NULL,
    contract_version VARCHAR(100) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    declaration_json CLOB NOT NULL,
    request_sha VARCHAR(64) NOT NULL,
    evidence_ref VARCHAR(500) NOT NULL,
    evidence_sha VARCHAR(64) NOT NULL,
    retention_ref VARCHAR(200) NOT NULL,
    retention_version BIGINT NOT NULL,
    retention_evidence_sha VARCHAR(64) NOT NULL,
    collected_at TIMESTAMP NOT NULL,
    valid_until TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    row_version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (source_id, contract_version),
    CONSTRAINT uq_con4_source_version UNIQUE (source_id, source_version),
    CONSTRAINT ck_con4_source_status CHECK (status IN ('CANDIDATE','ACTIVE','WITHDRAWN')),
    CONSTRAINT ck_con4_source_row CHECK (row_version >= 1 AND retention_version >= 1),
    CONSTRAINT ck_con4_source_validity CHECK (valid_until > collected_at)
);

CREATE TABLE con4_source_event (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    source_id VARCHAR(200) NOT NULL,
    contract_version VARCHAR(100) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    actor_ref VARCHAR(300) NOT NULL,
    request_sha VARCHAR(64) NOT NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con4_event_contract FOREIGN KEY (source_id, contract_version)
        REFERENCES con4_source_contract(source_id, contract_version),
    CONSTRAINT ck_con4_event_operation CHECK (operation IN ('REGISTER','WITHDRAW'))
);

CREATE TABLE con4_source_request (
    request_id VARCHAR(200) NOT NULL PRIMARY KEY,
    request_sha VARCHAR(64) NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    contract_version VARCHAR(100) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    resulting_version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con4_request_contract FOREIGN KEY (source_id, contract_version)
        REFERENCES con4_source_contract(source_id, contract_version)
);

CREATE INDEX ix_con4_source_status ON con4_source_contract(status);
