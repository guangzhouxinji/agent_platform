CREATE TABLE enf7_conduct_rule (
    rule_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    organization_id VARCHAR(200) NOT NULL, behavior_type VARCHAR(48) NOT NULL,
    state VARCHAR(16) NOT NULL, payload CLOB NOT NULL, created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(rule_id, revision),
    CONSTRAINT ck_enf7_rule_version CHECK(revision > 0),
    CONSTRAINT ck_enf7_rule_state CHECK(state IN ('DRAFT','ACTIVE'))
);

CREATE TABLE enf7_conduct_disabled (
    rule_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL, disabled_at TIMESTAMP NOT NULL,
    PRIMARY KEY(rule_id, revision),
    CONSTRAINT fk_enf7_disabled_rule FOREIGN KEY(rule_id, revision)
        REFERENCES enf7_conduct_rule(rule_id, revision)
);

CREATE TABLE enf7_conduct_report (
    analysis_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    organization_id VARCHAR(200) NOT NULL, evidence_organization_id VARCHAR(120) NOT NULL,
    evidence_organization_revision BIGINT NOT NULL, rule_id VARCHAR(120) NOT NULL,
    rule_revision BIGINT NOT NULL, payload CLOB NOT NULL, created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(analysis_id, revision),
    CONSTRAINT fk_enf7_report_evidence FOREIGN KEY(evidence_organization_id, evidence_organization_revision)
        REFERENCES enf4_organization(organization_id, revision),
    CONSTRAINT fk_enf7_report_rule FOREIGN KEY(rule_id, rule_revision)
        REFERENCES enf7_conduct_rule(rule_id, revision),
    CONSTRAINT ck_enf7_report_version CHECK(revision > 0)
);

CREATE TABLE enf7_conduct_receipt_lock (lock_id INTEGER PRIMARY KEY);
INSERT INTO enf7_conduct_receipt_lock(lock_id) VALUES(0);
INSERT INTO enf7_conduct_receipt_lock(lock_id) VALUES(1);
INSERT INTO enf7_conduct_receipt_lock(lock_id) VALUES(2);
INSERT INTO enf7_conduct_receipt_lock(lock_id) VALUES(3);
INSERT INTO enf7_conduct_receipt_lock(lock_id) VALUES(4);
INSERT INTO enf7_conduct_receipt_lock(lock_id) VALUES(5);
INSERT INTO enf7_conduct_receipt_lock(lock_id) VALUES(6);
INSERT INTO enf7_conduct_receipt_lock(lock_id) VALUES(7);

CREATE TABLE enf7_conduct_receipt (
    request_key VARCHAR(120) PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(40) NOT NULL,
    object_id VARCHAR(120) NOT NULL, object_revision BIGINT NOT NULL, created_at TIMESTAMP NOT NULL
);

CREATE TABLE enf7_conduct_event (
    event_id VARCHAR(36) PRIMARY KEY, request_key VARCHAR(120) NOT NULL UNIQUE,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(40) NOT NULL,
    object_id VARCHAR(120) NOT NULL, object_revision BIGINT NOT NULL, created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enf7_event_receipt FOREIGN KEY(request_key)
        REFERENCES enf7_conduct_receipt(request_key)
);
