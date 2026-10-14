CREATE TABLE enf10_document_rule_attempt (
 attempt_id VARCHAR(36) PRIMARY KEY, rule_id VARCHAR(120) NOT NULL,
 revision BIGINT NOT NULL, actor_ref VARCHAR(200) NOT NULL,
 action_code VARCHAR(16) NOT NULL, outcome VARCHAR(24) NOT NULL,
 artifact_key VARCHAR(500) NOT NULL, artifact_sha256 VARCHAR(64) NOT NULL,
 payload CLOB NOT NULL, created_at TIMESTAMP NOT NULL,
 CONSTRAINT ck_enf10_attempt_action CHECK(action_code IN ('VALIDATE','TRIAL')),
 CONSTRAINT ck_enf10_attempt_outcome CHECK(outcome IN ('COMPLETED','REVIEW_REQUIRED','FAILED')),
 CONSTRAINT fk_enf10_attempt_rule FOREIGN KEY(rule_id,revision) REFERENCES enf10_document_rule(rule_id,revision)
);
CREATE TABLE enf10_document_rule_attempt_event (
 attempt_id VARCHAR(36) PRIMARY KEY, rule_id VARCHAR(120) NOT NULL,
 revision BIGINT NOT NULL, actor_ref VARCHAR(200) NOT NULL,
 action_code VARCHAR(16) NOT NULL, outcome VARCHAR(24) NOT NULL, created_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_enf10_attempt_event FOREIGN KEY(attempt_id) REFERENCES enf10_document_rule_attempt(attempt_id)
);
