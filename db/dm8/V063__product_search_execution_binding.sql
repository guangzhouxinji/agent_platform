ALTER TABLE con4_search_attempt ADD fencing_token BIGINT NOT NULL;
ALTER TABLE con4_search_attempt ADD CONSTRAINT fk_con4_search_execution
    FOREIGN KEY (attempt_id) REFERENCES con3_execution_lease(attempt_id);
ALTER TABLE con4_search_attempt ADD CONSTRAINT ck_con4_search_fencing_token
    CHECK (fencing_token > 0);
