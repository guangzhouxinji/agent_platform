ALTER TABLE con3_task ADD cancel_epoch BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE con3_task ADD CONSTRAINT ck_con3_task_cancel_epoch CHECK (cancel_epoch >= 0);
