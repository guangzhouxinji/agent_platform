CREATE TABLE conp_prompt_cleanup_receipt (
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
    CONSTRAINT uq_conp_pc_receipt UNIQUE(receipt_id),
    CONSTRAINT uq_conp_pc_key UNIQUE(workspace_id,prompt_id,revision_id,subject_id,certificate_sha,operation,key_sha),
    CONSTRAINT uq_conp_pc_revision UNIQUE(revision_id),
    CONSTRAINT uq_conp_pc_before UNIQUE(prompt_id,generation,before_sha),
    CONSTRAINT uq_conp_pc_after UNIQUE(prompt_id,generation,after_sha),
    CONSTRAINT fk_conp_pc_workspace FOREIGN KEY(workspace_id) REFERENCES conp_workspace(workspace_id),
    CONSTRAINT fk_conp_pc_prompt FOREIGN KEY(prompt_id) REFERENCES conp_prompt(prompt_id),
    CONSTRAINT fk_conp_pc_revision FOREIGN KEY(revision_id) REFERENCES conp_revision(revision_id),
    CONSTRAINT fk_conp_pc_head FOREIGN KEY(head_revision_id) REFERENCES conp_revision(revision_id),
    CONSTRAINT ck_conp_pc CHECK(operation='BODY_CLEAR' AND generation>0 AND request_id<>receipt_id AND before_sha<>after_sha
        AND first_attempt<intent_until AND intent_until<=receipt_until AND intent_until<=closure_scope_until
        AND closure_scope_until<=receipt_until AND LENGTH(certificate_sha)=64 AND LENGTH(key_sha)=64
        AND LENGTH(request_sha)=64 AND LENGTH(before_sha)=64 AND LENGTH(after_sha)=64
        AND LENGTH(result_sha)=64 AND LENGTH(prepared_sha)=64)
);
