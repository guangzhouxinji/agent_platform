-- Removing the fence safety constraint is permitted only with no command history.
DECLARE
 history_count BIGINT;
BEGIN
 SELECT COUNT(*) INTO history_count FROM con5_start_command;
 IF history_count > 0 THEN
  RAISE_APPLICATION_ERROR(-20001, 'Nonempty comparison start history prohibits fence reverse migration');
 END IF;
END;
/
ALTER TABLE con5_start_command DROP CONSTRAINT ck_con5_start_fence_present;
