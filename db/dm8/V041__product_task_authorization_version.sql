ALTER TABLE con3_task ADD authorization_version BIGINT DEFAULT 1 NOT NULL;
ALTER TABLE con3_task ADD CONSTRAINT ck_con3_task_authorization_version
    CHECK (authorization_version >= 1);
