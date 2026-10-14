ALTER TABLE con4_search_attempt ADD CONSTRAINT fk_con4_search_execution_history
    FOREIGN KEY (attempt_id,task_id,fencing_token)
        REFERENCES con3_execution_attempt(attempt_id,task_id,fencing_token);
ALTER TABLE con4_search_attempt DROP CONSTRAINT fk_con4_search_execution;
