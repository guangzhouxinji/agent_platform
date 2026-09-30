CREATE TABLE enf5_work_package_revision (
    package_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    owner_organization VARCHAR(200) NOT NULL, evidence_organization_id VARCHAR(120) NOT NULL,
    evidence_organization_revision BIGINT NOT NULL, payload CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL, PRIMARY KEY(package_id, revision),
    CONSTRAINT fk_enf5_package_evidence FOREIGN KEY(evidence_organization_id, evidence_organization_revision)
        REFERENCES enf4_organization(organization_id, revision),
    CONSTRAINT ck_enf5_package_revision CHECK(revision > 0)
);

CREATE TABLE enf5_human_request (
    request_id VARCHAR(120) PRIMARY KEY, package_id VARCHAR(120) NOT NULL,
    package_revision BIGINT NOT NULL, request_revision BIGINT NOT NULL,
    request_state VARCHAR(32) NOT NULL, payload CLOB NOT NULL, created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enf5_request_package FOREIGN KEY(package_id, package_revision)
        REFERENCES enf5_work_package_revision(package_id, revision),
    CONSTRAINT ck_enf5_request_revision CHECK(request_revision > 0),
    CONSTRAINT ck_enf5_request_state CHECK(request_state IN ('PENDING','ACCEPTED','RETURNED','WITHDRAWN'))
);

CREATE TABLE enf5_human_result_revision (
    request_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    payload CLOB NOT NULL, created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(request_id, revision),
    CONSTRAINT fk_enf5_result_request FOREIGN KEY(request_id) REFERENCES enf5_human_request(request_id)
);

CREATE TABLE enf5_handoff_receipt_lock (lock_id INTEGER PRIMARY KEY);
INSERT INTO enf5_handoff_receipt_lock(lock_id) VALUES(0);
INSERT INTO enf5_handoff_receipt_lock(lock_id) VALUES(1);
INSERT INTO enf5_handoff_receipt_lock(lock_id) VALUES(2);
INSERT INTO enf5_handoff_receipt_lock(lock_id) VALUES(3);
INSERT INTO enf5_handoff_receipt_lock(lock_id) VALUES(4);
INSERT INTO enf5_handoff_receipt_lock(lock_id) VALUES(5);
INSERT INTO enf5_handoff_receipt_lock(lock_id) VALUES(6);
INSERT INTO enf5_handoff_receipt_lock(lock_id) VALUES(7);

CREATE TABLE enf5_handoff_receipt (
    request_key VARCHAR(120) PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(40) NOT NULL,
    object_id VARCHAR(120) NOT NULL, object_revision BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE enf5_human_event (
    event_id VARCHAR(36) PRIMARY KEY, request_key VARCHAR(120) NOT NULL UNIQUE,
    object_id VARCHAR(120) NOT NULL, object_revision BIGINT NOT NULL,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(40) NOT NULL, created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enf5_event_receipt FOREIGN KEY(request_key) REFERENCES enf5_handoff_receipt(request_key)
);
