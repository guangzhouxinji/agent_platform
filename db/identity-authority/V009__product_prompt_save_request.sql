-- Extend the immutable request scalars without rewriting the CREATE format or history.
ALTER TABLE idp_prompt_request DROP CONSTRAINT ck_idp_request;

ALTER TABLE idp_prompt_request ADD CONSTRAINT ck_idp_request CHECK(
    kind IN ('GUIDE','SYSTEM')
    AND scope_version>0 AND state IN ('PENDING','READY')
    AND body_until>prepared_at AND audit_until>=body_until
    AND intent_until>prepared_at AND intent_until<=body_until
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
);
