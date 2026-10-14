CREATE TABLE con5_matrix_invocation (
 attempt_id VARCHAR(36) PRIMARY KEY, approval_sha CHAR(64) NOT NULL, request_sha CHAR(64) NOT NULL,
 configuration_version VARCHAR(200) NOT NULL, status VARCHAR(16) NOT NULL,
 response_sha CHAR(64), response_json CLOB, created_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_con5_matrix_invoke FOREIGN KEY(attempt_id) REFERENCES con5_candidate_attempt(attempt_id),
 CONSTRAINT ck_con5_matrix_invoke CHECK(status IN ('RESERVED','RECEIVED','REJECTED','COMMITTED'))
);
CREATE TABLE con5_matrix_report (
 report_id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, comparison_id VARCHAR(36) NOT NULL,
 candidate_id VARCHAR(64) NOT NULL, attempt_id VARCHAR(36) NOT NULL UNIQUE, fencing_token BIGINT NOT NULL,
 input_sha CHAR(64) NOT NULL, report_sha CHAR(64) NOT NULL, package_json CLOB NOT NULL,
 created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_con5_matrix_input FOREIGN KEY(task_id,comparison_id) REFERENCES con5_comparison_input(task_id,comparison_id),
 CONSTRAINT fk_con5_matrix_candidate FOREIGN KEY(comparison_id,candidate_id,attempt_id,fencing_token)
 REFERENCES con5_candidate_attempt(comparison_id,candidate_id,attempt_id,fencing_token),
 CONSTRAINT fk_con5_matrix_response FOREIGN KEY(attempt_id) REFERENCES con5_matrix_invocation(attempt_id),
 CONSTRAINT ck_con5_matrix_report CHECK(fencing_token>0 AND expires_at>created_at)
);
CREATE TABLE con5_matrix_row (
 report_id VARCHAR(36) NOT NULL, ordinal BIGINT NOT NULL, feature_id VARCHAR(200) NOT NULL,
 branch_sha CHAR(64) NOT NULL, row_json CLOB NOT NULL, row_sha CHAR(64) NOT NULL,
 PRIMARY KEY(report_id,ordinal), CONSTRAINT uq_con5_matrix_row UNIQUE(report_id,feature_id,branch_sha),
 CONSTRAINT fk_con5_matrix_row FOREIGN KEY(report_id) REFERENCES con5_matrix_report(report_id),
 CONSTRAINT ck_con5_matrix_row CHECK(ordinal>=0)
);
CREATE TABLE con5_matrix_citation (
 report_id VARCHAR(36) NOT NULL, row_ordinal BIGINT NOT NULL, citation_ordinal BIGINT NOT NULL,
 citation_json CLOB NOT NULL, citation_sha CHAR(64) NOT NULL,
 PRIMARY KEY(report_id,row_ordinal,citation_ordinal),
 CONSTRAINT fk_con5_matrix_citation FOREIGN KEY(report_id,row_ordinal) REFERENCES con5_matrix_row(report_id,ordinal),
 CONSTRAINT ck_con5_matrix_citation CHECK(citation_ordinal>=0)
);
CREATE TABLE con5_matrix_receipt (
 attempt_id VARCHAR(36) PRIMARY KEY, report_id VARCHAR(36) NOT NULL UNIQUE,
 report_sha CHAR(64) NOT NULL, receipt_json CLOB NOT NULL, receipt_sha CHAR(64) NOT NULL,
 CONSTRAINT fk_con5_matrix_receipt FOREIGN KEY(report_id) REFERENCES con5_matrix_report(report_id),
 CONSTRAINT fk_con5_matrix_receipt_attempt FOREIGN KEY(attempt_id) REFERENCES con5_candidate_attempt(attempt_id)
);
