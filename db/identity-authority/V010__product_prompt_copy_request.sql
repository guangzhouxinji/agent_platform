-- COPY retains its exact source revision as finite scalars, never source plaintext.
-- CREATE/SAVE keep their original encoding and do not require these columns at runtime.
ALTER TABLE idp_prompt_request ADD source_workspace_id VARCHAR(36);
ALTER TABLE idp_prompt_request ADD source_prompt_id VARCHAR(36);
ALTER TABLE idp_prompt_request ADD source_revision_id VARCHAR(36);
ALTER TABLE idp_prompt_request ADD source_revision_number BIGINT;
ALTER TABLE idp_prompt_request ADD source_record_sha CHAR(64);
ALTER TABLE idp_prompt_request ADD source_body_until TIMESTAMP;
ALTER TABLE idp_prompt_request ADD source_audit_until TIMESTAMP;

ALTER TABLE idp_prompt_request DROP CONSTRAINT ck_idp_request;

ALTER TABLE idp_prompt_request ADD CONSTRAINT ck_idp_request CHECK(
    kind IN ('GUIDE','SYSTEM')
    AND scope_version>0 AND state IN ('PENDING','READY')
    AND body_until>prepared_at AND audit_until>=body_until
    AND intent_until>prepared_at AND intent_until<=body_until
    AND (
        (
            source_workspace_id IS NULL AND source_prompt_id IS NULL
            AND source_revision_id IS NULL AND source_revision_number IS NULL
            AND source_record_sha IS NULL AND source_body_until IS NULL
            AND source_audit_until IS NULL
            AND (
                (operation='CREATE'
                    AND expected_generation=0 AND expected_revision_id IS NULL AND target_generation=1
                    AND prepared_format='PROMPT_PREPARED_SCALARS/v1' AND request_format='PROMPT_CREATE_COMMAND/v1'
                    AND container_until=body_until AND control_until=audit_until)
                OR
                (operation='SAVE'
                    AND expected_generation>0 AND expected_generation<9223372036854775807
                    AND expected_revision_id IS NOT NULL AND target_generation=expected_generation+1
                    AND prepared_format='PROMPT_SAVE_PREPARED_SCALARS/v1' AND request_format='PROMPT_SAVE_COMMAND/v1'
                    AND container_until>=body_until AND control_until>=audit_until
                    AND container_until<=control_until)
            )
        )
        OR
        (
            operation='COPY'
            AND source_workspace_id IS NOT NULL AND source_prompt_id IS NOT NULL
            AND source_revision_id IS NOT NULL AND source_revision_number IS NOT NULL
            AND source_record_sha IS NOT NULL AND source_body_until IS NOT NULL
            AND source_audit_until IS NOT NULL
            AND source_revision_number>0 AND source_audit_until>=source_body_until
            AND source_prompt_id<>prompt_id AND source_revision_id<>target_revision_id
            AND body_until<=source_body_until AND audit_until<=source_audit_until
            AND expected_generation=0 AND expected_revision_id IS NULL AND target_generation=1
            AND prepared_format='PROMPT_COPY_PREPARED_SCALARS/v1' AND request_format='PROMPT_COPY_COMMAND/v1'
            AND container_until=body_until AND control_until=audit_until
        )
    )
);
