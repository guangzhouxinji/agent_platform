CREATE TABLE enf12_document_draft_export (
 export_id VARCHAR(120) PRIMARY KEY, draft_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
 content_sha256 VARCHAR(64) NOT NULL, artifact_key VARCHAR(500) NOT NULL, artifact_sha256 VARCHAR(64) NOT NULL,
 renderer_version VARCHAR(120) NOT NULL, payload CLOB NOT NULL, actor_ref VARCHAR(200) NOT NULL,
 request_key VARCHAR(120) NOT NULL UNIQUE, created_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_enf12_export_revision FOREIGN KEY(draft_id,revision) REFERENCES enf12_document_draft_revision(draft_id,revision)
);
