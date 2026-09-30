ALTER TABLE con3_event DROP CONSTRAINT ck_con3_event_operation;
ALTER TABLE con3_event ADD CONSTRAINT ck_con3_event_operation
    CHECK (operation IN ('INITIAL_CREATE','MESSAGE_ACCEPTED','GOAL_CONFIRMED',
        'USER_CANCELLED','SESSION_RESUMED'));
ALTER TABLE con3_command_receipt DROP CONSTRAINT ck_con3_command_operation;
ALTER TABLE con3_command_receipt ADD CONSTRAINT ck_con3_command_operation
    CHECK (operation IN ('MESSAGE_ACCEPTED','GOAL_CONFIRMED','USER_CANCELLED','SESSION_RESUMED'));
