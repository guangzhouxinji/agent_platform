-- Every request relation must be empty before reverse migration can remove any table.
DECLARE
 history_count BIGINT;
BEGIN
 SELECT COUNT(*) INTO history_count FROM con6_human_receipt;
 IF history_count > 0 THEN
  RAISE_APPLICATION_ERROR(-20001, 'Human receipts prohibit reverse migration');
 END IF;
 SELECT COUNT(*) INTO history_count FROM con6_human_event;
 IF history_count > 0 THEN
  RAISE_APPLICATION_ERROR(-20001, 'Human events prohibit reverse migration');
 END IF;
 SELECT COUNT(*) INTO history_count FROM con6_human_handling;
 IF history_count > 0 THEN
  RAISE_APPLICATION_ERROR(-20001, 'Human handling prohibits reverse migration');
 END IF;
 SELECT COUNT(*) INTO history_count FROM con6_human_package;
 IF history_count > 0 THEN
  RAISE_APPLICATION_ERROR(-20001, 'Human packages prohibit reverse migration');
 END IF;
END;
/
DROP TABLE con6_human_receipt;
DROP TABLE con6_human_event;
DROP TABLE con6_human_handling;
DROP TABLE con6_human_package;
