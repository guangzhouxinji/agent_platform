CREATE TABLE enf6_procedure_rule (
    rule_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    organization_id VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    payload CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (rule_id, revision)
);
CREATE TABLE enf6_procedure_disabled (
    rule_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    disabled_at TIMESTAMP NOT NULL,
    PRIMARY KEY (rule_id, revision)
);
CREATE TABLE enf6_procedure_report (
    report_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    organization_id VARCHAR(128) NOT NULL,
    case_ref VARCHAR(128) NOT NULL,
    case_version VARCHAR(128) NOT NULL,
    payload CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (report_id, revision)
);
CREATE TABLE enf6_procedure_receipt_lock (lock_id INT PRIMARY KEY);
INSERT INTO enf6_procedure_receipt_lock(lock_id) VALUES (0);
INSERT INTO enf6_procedure_receipt_lock(lock_id) VALUES (1);
INSERT INTO enf6_procedure_receipt_lock(lock_id) VALUES (2);
INSERT INTO enf6_procedure_receipt_lock(lock_id) VALUES (3);
INSERT INTO enf6_procedure_receipt_lock(lock_id) VALUES (4);
INSERT INTO enf6_procedure_receipt_lock(lock_id) VALUES (5);
INSERT INTO enf6_procedure_receipt_lock(lock_id) VALUES (6);
INSERT INTO enf6_procedure_receipt_lock(lock_id) VALUES (7);
CREATE TABLE enf6_procedure_receipt (
    request_id VARCHAR(128) PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(128) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    result_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE TABLE enf6_procedure_event (
    event_id VARCHAR(36) PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    actor_ref VARCHAR(128) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    result_id VARCHAR(128) NOT NULL,
    revision BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
