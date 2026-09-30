ALTER TABLE con3_event DROP CONSTRAINT ck_con3_event_operation;
ALTER TABLE con3_event ADD CONSTRAINT ck_con3_event_operation CHECK (
    operation IN ('INITIAL_CREATE','TASK_CREATED','MESSAGE_ACCEPTED','GOAL_CONFIRMED',
                  'USER_CANCELLED','SESSION_RESUMED'));

CREATE TABLE con3_task_create_receipt (
    request_id VARCHAR(200) NOT NULL PRIMARY KEY,
    request_sha VARCHAR(64) NOT NULL,
    actor_issuer VARCHAR(300) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    organization_id VARCHAR(200) NOT NULL,
    task_id VARCHAR(36) NOT NULL UNIQUE,
    scope_id VARCHAR(36) NOT NULL UNIQUE,
    grant_id VARCHAR(36) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con3_create_actor FOREIGN KEY (actor_issuer,actor_subject)
        REFERENCES id2_subject(issuer,subject_id),
    CONSTRAINT fk_con3_create_task FOREIGN KEY (task_id,organization_id)
        REFERENCES con3_task(task_id,organization_id),
    CONSTRAINT fk_con3_create_scope FOREIGN KEY (scope_id) REFERENCES con3_data_scope(scope_id),
    CONSTRAINT fk_con3_create_grant FOREIGN KEY (grant_id) REFERENCES id2_grant(grant_id)
);
