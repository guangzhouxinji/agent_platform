CREATE TABLE enf4_evidence_rule (
    rule_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    organization_id VARCHAR(200) NOT NULL, case_type VARCHAR(120) NOT NULL,
    basis_ref VARCHAR(300), state VARCHAR(16) NOT NULL,
    created_by VARCHAR(200) NOT NULL, created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(rule_id, revision),
    CONSTRAINT ck_enf4_rule_revision CHECK (revision > 0),
    CONSTRAINT ck_enf4_rule_state CHECK (state IN ('DRAFT','ACTIVE','DISABLED'))
);

CREATE TABLE enf4_evidence_matter (
    rule_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    matter_id VARCHAR(120) NOT NULL, required_flag CHAR(1) NOT NULL,
    expected_material VARCHAR(400),
    PRIMARY KEY(rule_id, revision, matter_id),
    CONSTRAINT fk_enf4_matter_rule FOREIGN KEY(rule_id, revision)
        REFERENCES enf4_evidence_rule(rule_id, revision),
    CONSTRAINT ck_enf4_required CHECK (required_flag IN ('Y','N'))
);

CREATE TABLE enf4_evidence_receipt (
    request_id VARCHAR(120) PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(40) NOT NULL,
    result_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE enf4_evidence_receipt_lock (lock_id INTEGER PRIMARY KEY);
INSERT INTO enf4_evidence_receipt_lock(lock_id) VALUES (0);
INSERT INTO enf4_evidence_receipt_lock(lock_id) VALUES (1);
INSERT INTO enf4_evidence_receipt_lock(lock_id) VALUES (2);
INSERT INTO enf4_evidence_receipt_lock(lock_id) VALUES (3);
INSERT INTO enf4_evidence_receipt_lock(lock_id) VALUES (4);
INSERT INTO enf4_evidence_receipt_lock(lock_id) VALUES (5);
INSERT INTO enf4_evidence_receipt_lock(lock_id) VALUES (6);
INSERT INTO enf4_evidence_receipt_lock(lock_id) VALUES (7);

CREATE TABLE enf4_evidence_event (
    event_id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(120) NOT NULL UNIQUE,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(40) NOT NULL,
    rule_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enf4_event_receipt FOREIGN KEY(request_id)
        REFERENCES enf4_evidence_receipt(request_id)
);
