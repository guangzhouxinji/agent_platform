-- Receipt of a managed handoff without computation admission; no attempt or result.
CREATE TABLE con5_start_handoff_receipt (
 command_id VARCHAR(36) NOT NULL PRIMARY KEY,
 task_id VARCHAR(36) NOT NULL, comparison_id VARCHAR(36) NOT NULL, candidate_id VARCHAR(64) NOT NULL,
 input_sha CHAR(64) NOT NULL, request_id VARCHAR(180) NOT NULL, request_sha CHAR(64) NOT NULL,
 configuration_version VARCHAR(200) NOT NULL, status VARCHAR(24) NOT NULL,
 receipt_json CLOB NOT NULL, receipt_sha CHAR(64) NOT NULL, received_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_con5_handoff_command FOREIGN KEY(command_id) REFERENCES con5_start_command(command_id),
 CONSTRAINT ck_con5_handoff_status CHECK(status='ADMISSION_REQUIRED')
);
