CREATE TABLE idp_prompt_cleanup_request (
    request_id VARCHAR(36) PRIMARY KEY,
    receipt_id VARCHAR(36) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    prompt_id VARCHAR(36) NOT NULL,
    revision_id VARCHAR(36) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    certificate_sha CHAR(64) NOT NULL,
    key_sha CHAR(64) NOT NULL,
    request_sha CHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    head_revision_id VARCHAR(36) NOT NULL,
    before_sha CHAR(64) NOT NULL,
    after_sha CHAR(64) NOT NULL,
    result_sha CHAR(64) NOT NULL,
    prepared_sha CHAR(64) NOT NULL,
    first_attempt TIMESTAMP NOT NULL,
    intent_until TIMESTAMP NOT NULL,
    receipt_until TIMESTAMP NOT NULL,
    closure_scope_until TIMESTAMP NOT NULL,
    manifest_json CLOB NOT NULL,
    state VARCHAR(16) NOT NULL,
    CONSTRAINT uq_idp_pc_receipt UNIQUE(receipt_id),
    CONSTRAINT uq_idp_pc_key UNIQUE(workspace_id,prompt_id,revision_id,subject_id,certificate_sha,operation,key_sha),
    CONSTRAINT uq_idp_pc_revision UNIQUE(revision_id),
    CONSTRAINT uq_idp_pc_before UNIQUE(prompt_id,generation,before_sha),
    CONSTRAINT uq_idp_pc_after UNIQUE(prompt_id,generation,after_sha),
    CONSTRAINT fk_idp_pc_workspace FOREIGN KEY(workspace_id) REFERENCES idp_workspace(workspace_id),
    CONSTRAINT fk_idp_pc_prompt FOREIGN KEY(prompt_id) REFERENCES idp_prompt_control(prompt_id),
    CONSTRAINT fk_idp_pc_revision FOREIGN KEY(revision_id) REFERENCES idp_revision_witness(revision_id),
    CONSTRAINT fk_idp_pc_head FOREIGN KEY(head_revision_id) REFERENCES idp_revision_witness(revision_id),
    CONSTRAINT ck_idp_pc CHECK(operation='BODY_CLEAR' AND state IN ('PENDING','READY') AND generation>0
        AND request_id<>receipt_id AND before_sha<>after_sha AND first_attempt<intent_until
        AND intent_until<=receipt_until AND intent_until<=closure_scope_until AND closure_scope_until<=receipt_until
        AND LENGTH(certificate_sha)=64 AND LENGTH(key_sha)=64 AND LENGTH(request_sha)=64
        AND LENGTH(before_sha)=64 AND LENGTH(after_sha)=64 AND LENGTH(result_sha)=64 AND LENGTH(prepared_sha)=64)
);
ALTER TABLE idp_prompt_control ADD cleanup_request_id VARCHAR(36);
ALTER TABLE idp_prompt_control ADD CONSTRAINT fk_idp_prompt_cleanup
    FOREIGN KEY(cleanup_request_id) REFERENCES idp_prompt_cleanup_request(request_id);
ALTER TABLE idp_prompt_control DROP CONSTRAINT ck_idp_prompt;
ALTER TABLE idp_prompt_control ADD CONSTRAINT ck_idp_prompt CHECK(generation>=0 AND kind IN ('GUIDE','SYSTEM')
    AND container_until<=control_until
    AND ((state='READY' AND generation>0 AND head_revision_id IS NOT NULL AND cleanup_request_id IS NULL
        AND intent_id IS NULL AND operation IS NULL AND key_sha IS NULL AND request_sha IS NULL AND before_sha IS NULL
        AND expected_after_sha IS NULL AND target_generation IS NULL AND target_revision_id IS NULL AND intent_until IS NULL)
    OR (state='PENDING' AND cleanup_request_id IS NULL AND intent_id IS NOT NULL AND operation IS NOT NULL AND operation IN ('CREATE','SAVE','COPY')
        AND key_sha IS NOT NULL AND request_sha IS NOT NULL AND before_sha IS NOT NULL
        AND expected_after_sha IS NOT NULL AND target_generation IS NOT NULL AND target_generation=generation+1
        AND target_revision_id IS NOT NULL AND intent_until IS NOT NULL)
    OR (state='CLEANUP_PENDING' AND generation>0 AND head_revision_id IS NOT NULL AND cleanup_request_id IS NOT NULL
        AND intent_id IS NULL AND operation IS NULL AND key_sha IS NULL AND request_sha IS NULL AND before_sha IS NULL
        AND expected_after_sha IS NULL AND target_generation IS NULL AND target_revision_id IS NULL AND intent_until IS NULL)));
