-- Explicit human single-candidate command receipts. Not part of fixed A's five migrations.
CREATE TABLE con5_start_command (
 command_id VARCHAR(36) NOT NULL PRIMARY KEY,
 task_id VARCHAR(36) NOT NULL, comparison_id VARCHAR(36) NOT NULL, candidate_id VARCHAR(64) NOT NULL,
 input_sha CHAR(64) NOT NULL, task_version BIGINT NOT NULL, cancel_epoch BIGINT NOT NULL,
 actor_issuer VARCHAR(300) NOT NULL, actor_subject VARCHAR(200) NOT NULL,
 request_id VARCHAR(180) NOT NULL, request_sha CHAR(64) NOT NULL,
 service_subject VARCHAR(200) NOT NULL, configuration_version VARCHAR(200) NOT NULL,
 delegation_id VARCHAR(36), attempt_id VARCHAR(36), fencing_token BIGINT, lease_until TIMESTAMP,
 status VARCHAR(24) NOT NULL, error_code VARCHAR(80), created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL,
 CONSTRAINT uq_con5_start_request UNIQUE(task_id,actor_issuer,actor_subject,request_id),
 CONSTRAINT fk_con5_start_input FOREIGN KEY(task_id,comparison_id) REFERENCES con5_comparison_input(task_id,comparison_id),
 CONSTRAINT fk_con5_start_candidate FOREIGN KEY(comparison_id,candidate_id) REFERENCES con5_comparison_candidate(comparison_id,candidate_id),
 CONSTRAINT fk_con5_start_delegation FOREIGN KEY(delegation_id) REFERENCES con5_comparison_delegation(delegation_id),
 CONSTRAINT fk_con5_start_attempt FOREIGN KEY(attempt_id) REFERENCES con5_candidate_attempt(attempt_id),
 CONSTRAINT ck_con5_start_command CHECK(task_version>0 AND cancel_epoch>=0 AND expires_at>created_at
  AND status IN ('ACCEPTED','DISPATCHING','RECONCILE_REQUIRED','COMMITTED','FAILED','CANCELLED')
  AND ((attempt_id IS NULL AND fencing_token IS NULL AND lease_until IS NULL)
   OR (attempt_id IS NOT NULL AND fencing_token>0 AND lease_until IS NOT NULL AND delegation_id IS NOT NULL))
  AND (status<>'COMMITTED' OR attempt_id IS NOT NULL))
);
CREATE INDEX ix_con5_start_candidate ON con5_start_command(task_id,comparison_id,candidate_id);
