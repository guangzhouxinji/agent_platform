-- DM8 guarded inverse: never destroy a committed recovery result or event.
DECLARE
 receipt_count BIGINT;
 event_count BIGINT;
BEGIN
 SELECT COUNT(*) INTO receipt_count FROM id2_activation_recovery_receipt;
 SELECT COUNT(*) INTO event_count FROM id2_subject_event WHERE operation='REISSUE';
 IF receipt_count > 0 OR event_count > 0 THEN
  RAISE_APPLICATION_ERROR(-20001, 'Activation recovery history prohibits reverse migration');
 END IF;
END;
/
DROP TABLE id2_activation_recovery_receipt;
ALTER TABLE id2_subject_event DROP CONSTRAINT ck_id2_subject_event_operation;
ALTER TABLE id2_subject_event ADD CONSTRAINT ck_id2_subject_event_operation
 CHECK (operation IN ('REGISTER','ACTIVATE','DISABLE','SERVICE_REGISTER','SERVICE_DISABLE'));
