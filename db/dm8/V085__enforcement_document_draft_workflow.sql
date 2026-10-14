CREATE TABLE enf12_document_draft_candidate (
 candidate_id VARCHAR(120) PRIMARY KEY, draft_id VARCHAR(120) NOT NULL, base_revision BIGINT NOT NULL,
 content_sha256 VARCHAR(64) NOT NULL, artifact_key VARCHAR(500) NOT NULL, artifact_sha256 VARCHAR(64) NOT NULL,
 payload CLOB NOT NULL, actor_ref VARCHAR(200) NOT NULL, request_key VARCHAR(120) NOT NULL UNIQUE, created_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_enf12_candidate_base FOREIGN KEY(draft_id,base_revision) REFERENCES enf12_document_draft_revision(draft_id,revision)
);
CREATE TABLE enf12_document_draft_review (
 draft_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL, review_sequence BIGINT NOT NULL, content_sha256 VARCHAR(64) NOT NULL,
 outcome VARCHAR(32) NOT NULL, payload CLOB NOT NULL, actor_ref VARCHAR(200) NOT NULL, request_key VARCHAR(120) NOT NULL UNIQUE, created_at TIMESTAMP NOT NULL,
 PRIMARY KEY(draft_id,revision,review_sequence),
 CONSTRAINT fk_enf12_review_revision FOREIGN KEY(draft_id,revision) REFERENCES enf12_document_draft_revision(draft_id,revision),
 CONSTRAINT ck_enf12_review_outcome CHECK(outcome IN ('SUBMITTED','APPROVED','RETURNED'))
);
