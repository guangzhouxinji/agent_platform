CREATE TABLE idp_workspace (
    workspace_id VARCHAR(36) PRIMARY KEY,
    organization VARCHAR(200) NOT NULL,
    scope_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    policy_ref VARCHAR(200) NOT NULL,
    policy_sha CHAR(64) NOT NULL,
    registry_sha CHAR(64) NOT NULL,
    valid_until TIMESTAMP NOT NULL,
    control_state VARCHAR(16) NOT NULL,
    registry_intent_id VARCHAR(36),
    expected_registry_sha CHAR(64) NOT NULL,
    intent_until TIMESTAMP NOT NULL,
    CONSTRAINT ck_idp_workspace CHECK(scope_version>0 AND state IN ('ACTIVE','CLOSED')
        AND ((control_state='READY' AND registry_intent_id IS NULL) OR (control_state='PENDING' AND registry_intent_id IS NOT NULL)))
);
CREATE TABLE idp_prompt_control (
    prompt_id VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    generation BIGINT NOT NULL,
    head_revision_id VARCHAR(36),
    scalar_sha CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    intent_id VARCHAR(36),
    operation VARCHAR(16),
    key_sha CHAR(64),
    request_sha CHAR(64),
    before_sha CHAR(64),
    expected_after_sha CHAR(64),
    target_generation BIGINT,
    target_revision_id VARCHAR(36),
    intent_until TIMESTAMP,
    container_until TIMESTAMP NOT NULL,
    control_until TIMESTAMP NOT NULL,
    CONSTRAINT fk_idp_prompt_workspace FOREIGN KEY(workspace_id) REFERENCES idp_workspace(workspace_id),
    CONSTRAINT ck_idp_prompt CHECK(generation>=0 AND kind IN ('GUIDE','SYSTEM') AND container_until<=control_until
        AND ((state='READY' AND generation>0 AND head_revision_id IS NOT NULL AND intent_id IS NULL)
        OR (state='PENDING' AND intent_id IS NOT NULL AND operation IN ('CREATE','SAVE','COPY')
        AND key_sha IS NOT NULL AND request_sha IS NOT NULL AND before_sha IS NOT NULL
        AND expected_after_sha IS NOT NULL AND target_generation=generation+1 AND target_revision_id IS NOT NULL
        AND intent_until IS NOT NULL)))
);
CREATE TABLE idp_revision_witness (
    revision_id VARCHAR(36) PRIMARY KEY,
    prompt_id VARCHAR(36) NOT NULL,
    revision_number BIGINT NOT NULL,
    record_sha CHAR(64) NOT NULL,
    content_sha CHAR(64),
    submitted_at TIMESTAMP NOT NULL,
    body_until TIMESTAMP NOT NULL,
    audit_until TIMESTAMP NOT NULL,
    body_state VARCHAR(16) NOT NULL,
    result_sha CHAR(64) NOT NULL,
    key_sha CHAR(64) NOT NULL,
    request_sha CHAR(64) NOT NULL,
    issuer VARCHAR(300) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    CONSTRAINT uq_idp_revision UNIQUE(prompt_id,revision_number),
    CONSTRAINT fk_idp_revision_prompt FOREIGN KEY(prompt_id) REFERENCES idp_prompt_control(prompt_id),
    CONSTRAINT ck_idp_revision CHECK(revision_number>0 AND body_until>submitted_at AND audit_until>=body_until
        AND ((body_state='PRESENT' AND content_sha IS NOT NULL) OR body_state='CLEARED'))
);
