-- Never reverse a nonempty managed handoff history.
DECLARE
 history_count BIGINT;
BEGIN
 SELECT COUNT(*) INTO history_count FROM con5_start_handoff_receipt;
 IF history_count > 0 THEN
  RAISE_APPLICATION_ERROR(-20001, 'Nonempty handoff receipt history prohibits reverse migration');
 END IF;
END;
/
DROP TABLE con5_start_handoff_receipt;
