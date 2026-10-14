-- Populated exported originals are historical evidence and cannot be removed by downgrade.
DECLARE
 row_count INTEGER;
BEGIN
 SELECT COUNT(*) INTO row_count FROM enf12_document_draft_export;
 IF row_count>0 THEN RAISE_APPLICATION_ERROR(-20087,'Document draft export history forbids downgrade'); END IF;
END;
/
DROP TABLE enf12_document_draft_export;
