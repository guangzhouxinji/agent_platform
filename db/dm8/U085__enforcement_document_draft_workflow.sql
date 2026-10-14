-- Do not downgrade a populated workflow or delete its historical evidence.
DECLARE
 row_count INTEGER;
BEGIN
 SELECT (SELECT COUNT(*) FROM enf12_document_draft_candidate)+(SELECT COUNT(*) FROM enf12_document_draft_review) INTO row_count FROM DUAL;
 IF row_count>0 THEN RAISE_APPLICATION_ERROR(-20085,'Document draft workflow history forbids downgrade'); END IF;
END;
/
DROP TABLE enf12_document_draft_review;
DROP TABLE enf12_document_draft_candidate;
