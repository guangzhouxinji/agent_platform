-- Forward-only: preserve existing QUERY receipts and allow licensed DISPLAY-only scopes.
-- Do not restore the QUERY-required constraint on nonempty DISPLAY-only history.
ALTER TABLE con4_task_source_request DROP CONSTRAINT ck_con4_scope_query_grant;
ALTER TABLE con4_task_source_request ADD CONSTRAINT ck_con4_scope_nonempty_grant
    CHECK (query_grant_id IS NOT NULL OR display_grant_id IS NOT NULL);
