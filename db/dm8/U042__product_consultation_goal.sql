ALTER TABLE con3_task DROP CONSTRAINT ck_con3_task_state;
ALTER TABLE con3_task ADD CONSTRAINT ck_con3_task_state CHECK (state IN ('AWAITING_GOAL'));
ALTER TABLE con3_event DROP CONSTRAINT ck_con3_event_operation;
ALTER TABLE con3_event ADD CONSTRAINT ck_con3_event_operation CHECK (operation = 'INITIAL_CREATE');

DROP TABLE con3_command_receipt;
DROP TABLE con3_search_input;
DROP TABLE con3_goal_current;
DROP TABLE con3_goal_revision;
DROP TABLE con3_message;
DROP TABLE con3_session;
