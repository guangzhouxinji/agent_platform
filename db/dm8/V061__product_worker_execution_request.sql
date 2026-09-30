CREATE TABLE con3_execution_request (
    task_id VARCHAR(36) NOT NULL,
    service_subject VARCHAR(200) NOT NULL,
    request_id VARCHAR(180) NOT NULL,
    request_sha CHAR(64) NOT NULL,
    input_id VARCHAR(36) NOT NULL,
    delegation_id VARCHAR(36) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    fencing_token BIGINT NOT NULL,
    lease_until TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (task_id,service_subject,request_id),
    CONSTRAINT fk_con3_execution_request_task FOREIGN KEY (task_id) REFERENCES con3_task(task_id),
    CONSTRAINT fk_con3_execution_request_input FOREIGN KEY (task_id,input_id)
        REFERENCES con3_search_input(task_id,input_id),
    CONSTRAINT ck_con3_execution_request_token CHECK (fencing_token > 0)
);
