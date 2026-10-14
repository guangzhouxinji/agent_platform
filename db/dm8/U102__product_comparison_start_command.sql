-- Nonempty command history is not reversible. Never remove a persisted unknown external attempt.
DECLARE
 history_count BIGINT;
BEGIN
 SELECT COUNT(*) INTO history_count FROM con5_start_command;
 IF history_count > 0 THEN
  RAISE_APPLICATION_ERROR(-20001, 'Nonempty comparison start history prohibits reverse migration');
 END IF;
END;
/
DROP TABLE con5_start_command;
