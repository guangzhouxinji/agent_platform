CREATE TABLE enf12_document_draft_revision (
 draft_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL, organization_id VARCHAR(200) NOT NULL,
 review_state VARCHAR(32) NOT NULL, content_sha256 VARCHAR(64) NOT NULL,
 input_manifest_sha256 VARCHAR(64) NOT NULL, artifact_key VARCHAR(500) NOT NULL,
 artifact_sha256 VARCHAR(64) NOT NULL, payload CLOB NOT NULL, actor_ref VARCHAR(200) NOT NULL,
 created_at TIMESTAMP NOT NULL, PRIMARY KEY(draft_id,revision),
 CONSTRAINT ck_enf12_draft_revision CHECK(revision>0)
);
CREATE TABLE enf12_document_draft_lock (lock_id INTEGER PRIMARY KEY);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(0);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(1);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(2);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(3);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(4);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(5);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(6);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(7);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(8);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(9);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(10);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(11);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(12);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(13);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(14);
INSERT INTO enf12_document_draft_lock(lock_id) VALUES(15);
CREATE TABLE enf12_document_draft_receipt (
 request_key VARCHAR(120) PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL,
 actor_ref VARCHAR(200) NOT NULL, draft_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
 payload CLOB NOT NULL, created_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_enf12_receipt_draft FOREIGN KEY(draft_id,revision)
 REFERENCES enf12_document_draft_revision(draft_id,revision)
);
CREATE TABLE enf12_document_draft_event (
 event_id VARCHAR(36) PRIMARY KEY, request_key VARCHAR(120) NOT NULL UNIQUE,
 operation VARCHAR(32) NOT NULL, actor_ref VARCHAR(200) NOT NULL,
 draft_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL, created_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_enf12_event_receipt FOREIGN KEY(request_key) REFERENCES enf12_document_draft_receipt(request_key)
);
