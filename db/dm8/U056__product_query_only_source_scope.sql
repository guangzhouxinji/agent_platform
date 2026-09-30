ALTER TABLE con4_task_source_request DROP CONSTRAINT ck_con4_scope_query_grant;
ALTER TABLE con4_task_source_request ADD CONSTRAINT ck_con4_scope_grant_pair CHECK (
    (query_grant_id IS NULL AND display_grant_id IS NULL) OR
    (query_grant_id IS NOT NULL AND display_grant_id IS NOT NULL));
