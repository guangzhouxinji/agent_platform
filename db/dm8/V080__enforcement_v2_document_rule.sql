CREATE TABLE enf10_document_rule (
 rule_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
 organization_id VARCHAR(200) NOT NULL, state VARCHAR(24) NOT NULL,
 payload CLOB NOT NULL, actor_ref VARCHAR(200) NOT NULL, created_at TIMESTAMP NOT NULL,
 PRIMARY KEY(rule_id,revision),
 CONSTRAINT ck_enf10_rule_revision CHECK(revision > 0),
 CONSTRAINT ck_enf10_rule_state CHECK(state IN ('DRAFT','PUBLISHED','REVIEW_REQUIRED','DISABLED'))
);
CREATE TABLE enf10_document_rule_lock (lock_id INTEGER PRIMARY KEY);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(0);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(1);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(2);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(3);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(4);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(5);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(6);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(7);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(8);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(9);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(10);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(11);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(12);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(13);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(14);
INSERT INTO enf10_document_rule_lock(lock_id) VALUES(15);
CREATE TABLE enf10_document_rule_receipt (
 request_key VARCHAR(120) PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL,
 actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(40) NOT NULL,
 rule_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE TABLE enf10_document_rule_event (
 event_id VARCHAR(36) PRIMARY KEY, request_key VARCHAR(120) NOT NULL UNIQUE,
 rule_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL, actor_ref VARCHAR(200) NOT NULL,
 operation VARCHAR(40) NOT NULL, created_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_enf10_rule_event_receipt FOREIGN KEY(request_key) REFERENCES enf10_document_rule_receipt(request_key)
);

