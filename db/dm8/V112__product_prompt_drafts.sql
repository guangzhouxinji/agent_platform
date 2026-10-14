CREATE TABLE conp_workspace (
    workspace_id VARCHAR(36) PRIMARY KEY,
    organization VARCHAR(200) NOT NULL,
    scope_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    policy_ref VARCHAR(200) NOT NULL,
    policy_sha CHAR(64) NOT NULL,
    registry_sha CHAR(64) NOT NULL,
    valid_until TIMESTAMP NOT NULL,
    CONSTRAINT ck_conp_workspace CHECK(scope_version>0 AND state IN ('ACTIVE','CLOSED'))
);
CREATE TABLE conp_prompt (
    prompt_id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    head_revision_id VARCHAR(36) NOT NULL,
    generation BIGINT NOT NULL,
    scalar_sha CHAR(64) NOT NULL,
    container_until TIMESTAMP NOT NULL,
    control_until TIMESTAMP NOT NULL,
    CONSTRAINT fk_conp_prompt_workspace FOREIGN KEY(workspace_id) REFERENCES conp_workspace(workspace_id),
    CONSTRAINT ck_conp_prompt CHECK(generation>0 AND kind IN ('GUIDE','SYSTEM') AND container_until<=control_until)
);
CREATE TABLE conp_revision (
    revision_id VARCHAR(36) PRIMARY KEY,
    prompt_id VARCHAR(36) NOT NULL,
    revision_number BIGINT NOT NULL,
    revision_json CLOB,
    content_sha CHAR(64) NOT NULL,
    record_sha CHAR(64) NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    body_until TIMESTAMP NOT NULL,
    audit_until TIMESTAMP NOT NULL,
    body_state VARCHAR(16) NOT NULL,
    CONSTRAINT uq_conp_revision UNIQUE(prompt_id,revision_number),
    CONSTRAINT fk_conp_revision_prompt FOREIGN KEY(prompt_id) REFERENCES conp_prompt(prompt_id),
    CONSTRAINT ck_conp_revision CHECK(revision_number>0 AND body_until>submitted_at AND audit_until>=body_until
        AND ((body_state='PRESENT' AND revision_json IS NOT NULL) OR (body_state='CLEARED' AND revision_json IS NULL)))
);
CREATE TABLE conp_event (
    event_id VARCHAR(36) PRIMARY KEY,
    prompt_id VARCHAR(36) NOT NULL,
    revision_id VARCHAR(36) NOT NULL,
    generation BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    issuer VARCHAR(300) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    request_sha CHAR(64) NOT NULL,
    result_sha CHAR(64) NOT NULL,
    audit_until TIMESTAMP NOT NULL,
    CONSTRAINT uq_conp_event UNIQUE(prompt_id,generation),
    CONSTRAINT fk_conp_event_prompt FOREIGN KEY(prompt_id) REFERENCES conp_prompt(prompt_id),
    CONSTRAINT fk_conp_event_revision FOREIGN KEY(revision_id) REFERENCES conp_revision(revision_id),
    CONSTRAINT ck_conp_event CHECK(generation>0 AND operation IN ('CREATE','SAVE','COPY') AND audit_until>submitted_at)
);
CREATE TABLE conp_receipt (
    receipt_id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    issuer VARCHAR(300) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    key_sha CHAR(64) NOT NULL,
    request_sha CHAR(64) NOT NULL,
    prompt_id VARCHAR(36) NOT NULL,
    revision_id VARCHAR(36) NOT NULL,
    generation BIGINT NOT NULL,
    content_sha CHAR(64) NOT NULL,
    result_sha CHAR(64) NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    audit_until TIMESTAMP NOT NULL,
    CONSTRAINT uq_conp_receipt UNIQUE(workspace_id,issuer,subject_id,operation,key_sha),
    CONSTRAINT fk_conp_receipt_workspace FOREIGN KEY(workspace_id) REFERENCES conp_workspace(workspace_id),
    CONSTRAINT fk_conp_receipt_prompt FOREIGN KEY(prompt_id) REFERENCES conp_prompt(prompt_id),
    CONSTRAINT fk_conp_receipt_revision FOREIGN KEY(revision_id) REFERENCES conp_revision(revision_id),
    CONSTRAINT ck_conp_receipt CHECK(generation>0 AND operation IN ('CREATE','SAVE','COPY') AND audit_until>submitted_at)
);
CREATE INDEX ix_conp_prompt_workspace ON conp_prompt(workspace_id,prompt_id);
CREATE INDEX ix_conp_revision_page ON conp_revision(prompt_id,submitted_at,revision_id);
