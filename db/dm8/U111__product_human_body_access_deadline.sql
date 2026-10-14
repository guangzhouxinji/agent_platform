DECLARE
 history_count BIGINT;
BEGIN
 SELECT COUNT(*) INTO history_count FROM con6_human_package;
 IF history_count > 0 THEN
  RAISE_APPLICATION_ERROR(-20001, 'Human package history prohibits removing its access deadline');
 END IF;
END;
/
ALTER TABLE con6_human_package DROP CONSTRAINT ck_con6_body_access;
ALTER TABLE con6_human_package DROP COLUMN body_access_until;
