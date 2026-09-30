DROP TABLE con3_task_create_receipt;
ALTER TABLE con3_event DROP CONSTRAINT ck_con3_event_operation;
ALTER TABLE con3_event ADD CONSTRAINT ck_con3_event_operation CHECK (
    operation IN ('INITIAL_CREATE','MESSAGE_ACCEPTED','GOAL_CONFIRMED',
                  'USER_CANCELLED','SESSION_RESUMED'));
