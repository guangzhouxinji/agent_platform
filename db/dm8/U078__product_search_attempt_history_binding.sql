-- Reverse only while every persisted result still has its matching current slot.
-- Once continued results exist the old foreign key correctly refuses rollback.
ALTER TABLE con4_search_attempt ADD CONSTRAINT fk_con4_search_execution
    FOREIGN KEY (attempt_id) REFERENCES con3_execution_lease(attempt_id);
ALTER TABLE con4_search_attempt DROP CONSTRAINT fk_con4_search_execution_history;
