DECLARE
    retained_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO retained_count FROM con3_model_turn;
    IF retained_count > 0 THEN
        RAISE_APPLICATION_ERROR(-20089, 'Retained model turns prohibit destructive rollback');
    END IF;
END;
/
DROP TABLE con3_model_turn;
