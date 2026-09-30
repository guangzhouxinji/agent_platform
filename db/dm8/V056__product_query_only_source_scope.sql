ALTER TABLE con4_task_source_request DROP CONSTRAINT ck_con4_scope_grant_pair;
ALTER TABLE con4_task_source_request ADD CONSTRAINT ck_con4_scope_query_grant
    CHECK (query_grant_id IS NOT NULL);
