CREATE TABLE enf9_document_template_revision (
    template_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL, state VARCHAR(16) NOT NULL,
    organization_id VARCHAR(200) NOT NULL, name VARCHAR(300) NOT NULL, file_sha256 VARCHAR(64) NOT NULL,
    file_key VARCHAR(500) NOT NULL, payload CLOB NOT NULL, transition_evidence_ref VARCHAR(500),
    created_by VARCHAR(200) NOT NULL, created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(template_id, revision),
    CONSTRAINT ck_enf9_template_revision CHECK(revision > 0),
    CONSTRAINT ck_enf9_template_state CHECK(state IN ('DRAFT','PUBLISHED','DISABLED'))
);

CREATE TABLE enf9_document_template_preview (
    preview_id VARCHAR(36) PRIMARY KEY, template_id VARCHAR(120) NOT NULL, template_revision BIGINT NOT NULL,
    template_sha256 VARCHAR(64) NOT NULL, artifact_sha256 VARCHAR(64) NOT NULL, file_key VARCHAR(500) NOT NULL,
    payload CLOB NOT NULL, created_by VARCHAR(200) NOT NULL, created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enf9_preview_template FOREIGN KEY(template_id, template_revision)
        REFERENCES enf9_document_template_revision(template_id, revision)
);

CREATE TABLE enf9_document_template_receipt_lock (lock_id INTEGER PRIMARY KEY);
INSERT INTO enf9_document_template_receipt_lock(lock_id) VALUES(0);
INSERT INTO enf9_document_template_receipt_lock(lock_id) VALUES(1);
INSERT INTO enf9_document_template_receipt_lock(lock_id) VALUES(2);
INSERT INTO enf9_document_template_receipt_lock(lock_id) VALUES(3);
INSERT INTO enf9_document_template_receipt_lock(lock_id) VALUES(4);
INSERT INTO enf9_document_template_receipt_lock(lock_id) VALUES(5);
INSERT INTO enf9_document_template_receipt_lock(lock_id) VALUES(6);
INSERT INTO enf9_document_template_receipt_lock(lock_id) VALUES(7);

CREATE TABLE enf9_document_template_receipt (
    request_key VARCHAR(120) PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL, actor_ref VARCHAR(200) NOT NULL,
    operation VARCHAR(32) NOT NULL, result_type VARCHAR(300) NOT NULL, result_payload CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE enf9_document_template_event (
    event_id VARCHAR(36) PRIMARY KEY, request_key VARCHAR(120) NOT NULL UNIQUE,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(32) NOT NULL, created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enf9_template_event_receipt FOREIGN KEY(request_key)
        REFERENCES enf9_document_template_receipt(request_key)
);
