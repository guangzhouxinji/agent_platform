CREATE TABLE con5_candidate_attempt (
    attempt_id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL,
    comparison_id VARCHAR(36) NOT NULL, candidate_id VARCHAR(64) NOT NULL,
    input_sha CHAR(64) NOT NULL, task_version BIGINT NOT NULL, cancel_epoch BIGINT NOT NULL,
    worker_subject VARCHAR(200) NOT NULL, delegation_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(180) NOT NULL, request_sha CHAR(64) NOT NULL, originals_sha CHAR(64) NOT NULL,
    fencing_token BIGINT NOT NULL, status VARCHAR(24) NOT NULL, error_code VARCHAR(80),
    created_at TIMESTAMP NOT NULL, lease_until TIMESTAMP NOT NULL,
    CONSTRAINT uq_con5_candidate_request UNIQUE(comparison_id,candidate_id,worker_subject,request_id),
    CONSTRAINT uq_con5_candidate_token UNIQUE(comparison_id,candidate_id,attempt_id,fencing_token),
    CONSTRAINT fk_con5_attempt_input FOREIGN KEY(task_id,comparison_id)
        REFERENCES con5_comparison_input(task_id,comparison_id),
    CONSTRAINT fk_con5_attempt_candidate FOREIGN KEY(comparison_id,candidate_id)
        REFERENCES con5_comparison_candidate(comparison_id,candidate_id),
    CONSTRAINT fk_con5_attempt_delegation FOREIGN KEY(delegation_id)
        REFERENCES con5_comparison_delegation(delegation_id),
    CONSTRAINT ck_con5_candidate_attempt CHECK(task_version>0 AND cancel_epoch>=0 AND fencing_token>0
        AND lease_until>created_at AND status IN ('ACTIVE','FAILED','RECONCILE_REQUIRED','COMMITTED','CANCELLED'))
);
CREATE TABLE con5_candidate_lease (
    comparison_id VARCHAR(36) NOT NULL, candidate_id VARCHAR(64) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL, fencing_token BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL, lease_until TIMESTAMP NOT NULL,
    CONSTRAINT pk_con5_candidate_lease PRIMARY KEY(comparison_id,candidate_id),
    CONSTRAINT fk_con5_candidate_lease FOREIGN KEY(comparison_id,candidate_id,attempt_id,fencing_token)
        REFERENCES con5_candidate_attempt(comparison_id,candidate_id,attempt_id,fencing_token),
    CONSTRAINT ck_con5_candidate_lease CHECK(fencing_token>0
        AND status IN ('ACTIVE','FAILED','RECONCILE_REQUIRED','COMMITTED','CANCELLED'))
);
