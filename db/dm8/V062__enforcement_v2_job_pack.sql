CREATE TABLE enf8_job_pack_revision (
    package_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL, content_revision BIGINT NOT NULL,
    organization_id VARCHAR(200) NOT NULL, state VARCHAR(32) NOT NULL, sha256 VARCHAR(64) NOT NULL,
    payload CLOB NOT NULL, created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(package_id, revision),
    CONSTRAINT ck_enf8_pack_revision CHECK(revision > 0),
    CONSTRAINT ck_enf8_content_revision CHECK(content_revision >= 0),
    CONSTRAINT ck_enf8_pack_state CHECK(state IN ('COLLECTING','WAITING_DATA','READY','RUNNING','PASSED','FAILED'))
);

CREATE TABLE enf8_job_pack_receipt_lock (lock_id INTEGER PRIMARY KEY);
INSERT INTO enf8_job_pack_receipt_lock(lock_id) VALUES(0);
INSERT INTO enf8_job_pack_receipt_lock(lock_id) VALUES(1);
INSERT INTO enf8_job_pack_receipt_lock(lock_id) VALUES(2);
INSERT INTO enf8_job_pack_receipt_lock(lock_id) VALUES(3);
INSERT INTO enf8_job_pack_receipt_lock(lock_id) VALUES(4);
INSERT INTO enf8_job_pack_receipt_lock(lock_id) VALUES(5);
INSERT INTO enf8_job_pack_receipt_lock(lock_id) VALUES(6);
INSERT INTO enf8_job_pack_receipt_lock(lock_id) VALUES(7);

CREATE TABLE enf8_job_pack_file (
    file_key VARCHAR(36) PRIMARY KEY, package_id VARCHAR(120) NOT NULL, package_revision BIGINT DEFAULT 1 NOT NULL,
    evidence_ref VARCHAR(200) NOT NULL,
    sha256 VARCHAR(64) NOT NULL, original_name VARCHAR(500) NOT NULL, uploaded_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_enf8_file_ref UNIQUE(package_id, evidence_ref),
    CONSTRAINT fk_enf8_file_package FOREIGN KEY(package_id, package_revision)
        REFERENCES enf8_job_pack_revision(package_id, revision)
);

CREATE TABLE enf8_job_pack_receipt (
    request_key VARCHAR(120) PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL, actor_ref VARCHAR(200) NOT NULL,
    operation VARCHAR(48) NOT NULL, package_id VARCHAR(120) NOT NULL, package_revision BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL, created_at TIMESTAMP NOT NULL
);

CREATE TABLE enf8_job_pack_event (
    event_id VARCHAR(36) PRIMARY KEY, request_key VARCHAR(120) NOT NULL UNIQUE,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(48) NOT NULL, package_id VARCHAR(120) NOT NULL,
    package_revision BIGINT NOT NULL, state VARCHAR(32) NOT NULL, created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enf8_event_receipt FOREIGN KEY(request_key) REFERENCES enf8_job_pack_receipt(request_key)
);

CREATE TABLE enf8_integration_trigger (
    request_id VARCHAR(36) PRIMARY KEY, package_id VARCHAR(120) NOT NULL, package_revision BIGINT NOT NULL,
    package_sha256 VARCHAR(64) NOT NULL, task_id VARCHAR(80) NOT NULL, state VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_enf8_trigger_package UNIQUE(package_id, package_revision),
    CONSTRAINT fk_enf8_trigger_package FOREIGN KEY(package_id, package_revision)
        REFERENCES enf8_job_pack_revision(package_id, revision),
    CONSTRAINT ck_enf8_trigger_state CHECK(state IN ('PENDING','ACCEPTED','FAILED'))
);
