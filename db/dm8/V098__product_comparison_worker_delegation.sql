CREATE TABLE con5_comparison_delegation (
    delegation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    comparison_id VARCHAR(36) NOT NULL,
    candidate_id VARCHAR(64) NOT NULL,
    input_sha CHAR(64) NOT NULL,
    binding_sha CHAR(64) NOT NULL,
    service_subject VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con5_compare_delegate FOREIGN KEY(delegation_id)
        REFERENCES con3_worker_delegation(delegation_id),
    CONSTRAINT fk_con5_compare_delegate_input FOREIGN KEY(task_id,comparison_id)
        REFERENCES con5_comparison_input(task_id,comparison_id),
    CONSTRAINT fk_con5_compare_delegate_candidate FOREIGN KEY(comparison_id,candidate_id)
        REFERENCES con5_comparison_candidate(comparison_id,candidate_id),
    CONSTRAINT ck_con5_compare_delegate_time CHECK(expires_at>created_at)
);
