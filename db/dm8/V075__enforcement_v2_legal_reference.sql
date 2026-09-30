CREATE TABLE enf9_legal_search (
    search_id VARCHAR(36) NOT NULL, revision BIGINT NOT NULL,
    organization_id VARCHAR(200) NOT NULL, target_type VARCHAR(48) NOT NULL,
    target_id VARCHAR(120) NOT NULL, target_revision BIGINT NOT NULL,
    search_state VARCHAR(16) NOT NULL, payload CLOB NOT NULL, created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(search_id, revision),
    CONSTRAINT ck_enf9_search_revision CHECK(revision > 0),
    CONSTRAINT ck_enf9_search_state CHECK(search_state IN ('COMPLETED','PARTIAL','FAILED'))
);

CREATE TABLE enf9_legal_reference (
    reference_id VARCHAR(36) NOT NULL, revision BIGINT NOT NULL,
    organization_id VARCHAR(200) NOT NULL, search_id VARCHAR(36) NOT NULL,
    search_revision BIGINT NOT NULL, source_id VARCHAR(160) NOT NULL, source_revision BIGINT NOT NULL,
    source_version VARCHAR(160) NOT NULL, article_id VARCHAR(160) NOT NULL,
    verbatim_sha256 VARCHAR(64) NOT NULL, review_state VARCHAR(16) NOT NULL,
    binding_state VARCHAR(20) NOT NULL, payload CLOB NOT NULL, created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(reference_id, revision),
    CONSTRAINT fk_enf9_reference_search FOREIGN KEY(search_id, search_revision)
        REFERENCES enf9_legal_search(search_id, revision),
    CONSTRAINT ck_enf9_reference_revision CHECK(revision > 0),
    CONSTRAINT ck_enf9_source_revision CHECK(source_revision > 0),
    CONSTRAINT ck_enf9_review_state CHECK(review_state IN ('PENDING','CONFIRMED','RETURNED')),
    CONSTRAINT ck_enf9_binding_state CHECK(binding_state IN ('ACTIVE','NEEDS_REVIEW','UNBOUND'))
);

CREATE INDEX ix_enf9_reference_history ON enf9_legal_reference(reference_id, revision);
CREATE INDEX ix_enf9_reference_target ON enf9_legal_search(organization_id, target_type, target_id, target_revision);

CREATE TABLE enf9_legal_receipt_lock (lock_id INTEGER PRIMARY KEY);
INSERT INTO enf9_legal_receipt_lock(lock_id) VALUES(0);
INSERT INTO enf9_legal_receipt_lock(lock_id) VALUES(1);
INSERT INTO enf9_legal_receipt_lock(lock_id) VALUES(2);
INSERT INTO enf9_legal_receipt_lock(lock_id) VALUES(3);
INSERT INTO enf9_legal_receipt_lock(lock_id) VALUES(4);
INSERT INTO enf9_legal_receipt_lock(lock_id) VALUES(5);
INSERT INTO enf9_legal_receipt_lock(lock_id) VALUES(6);
INSERT INTO enf9_legal_receipt_lock(lock_id) VALUES(7);

CREATE TABLE enf9_legal_receipt (
    request_key VARCHAR(120) PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(40) NOT NULL,
    object_id VARCHAR(120) NOT NULL, object_revision BIGINT NOT NULL, created_at TIMESTAMP NOT NULL
);

CREATE TABLE enf9_legal_event (
    event_id VARCHAR(36) PRIMARY KEY, request_key VARCHAR(120) NOT NULL UNIQUE,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(40) NOT NULL,
    object_id VARCHAR(120) NOT NULL, object_revision BIGINT NOT NULL, created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enf9_event_receipt FOREIGN KEY(request_key) REFERENCES enf9_legal_receipt(request_key)
);
