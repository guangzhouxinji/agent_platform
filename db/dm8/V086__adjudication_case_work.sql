CREATE TABLE adj2_case_work (
 case_id VARCHAR(120) PRIMARY KEY, organization VARCHAR(200) NOT NULL, data_scope VARCHAR(200) NOT NULL,
 head_version BIGINT NOT NULL, head_sha256 VARCHAR(64),
 CONSTRAINT ck_adj2_head CHECK(head_version>=0)
);
CREATE TABLE adj2_case_original (
 case_id VARCHAR(120) NOT NULL, source_sha256 VARCHAR(64) NOT NULL, original_text CLOB NOT NULL,
 PRIMARY KEY(case_id,source_sha256),
 CONSTRAINT fk_adj2_original_case FOREIGN KEY(case_id) REFERENCES adj2_case_work(case_id)
);
CREATE TABLE adj2_case_revision (
 case_id VARCHAR(120) NOT NULL, work_version BIGINT NOT NULL, input_version BIGINT NOT NULL,
 payload CLOB NOT NULL, payload_sha256 VARCHAR(64) NOT NULL, previous_sha256 VARCHAR(64),
 actor_ref VARCHAR(600) NOT NULL, action_code VARCHAR(50) NOT NULL, created_at TIMESTAMP NOT NULL,
 PRIMARY KEY(case_id,work_version),
 CONSTRAINT fk_adj2_revision_case FOREIGN KEY(case_id) REFERENCES adj2_case_work(case_id),
 CONSTRAINT ck_adj2_revision CHECK(work_version>=1 AND input_version>=1 AND input_version<=work_version)
);
CREATE TABLE adj2_case_audit (
 case_id VARCHAR(120) NOT NULL, work_version BIGINT NOT NULL, payload CLOB NOT NULL, payload_sha256 VARCHAR(64) NOT NULL,
 PRIMARY KEY(case_id,work_version),
 CONSTRAINT fk_adj2_audit_revision FOREIGN KEY(case_id,work_version) REFERENCES adj2_case_revision(case_id,work_version)
);
CREATE TABLE adj2_case_receipt (
 request_key VARCHAR(100) PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL, actor_identity VARCHAR(600) NOT NULL,
 case_id VARCHAR(120) NOT NULL, work_version BIGINT NOT NULL, action_code VARCHAR(50) NOT NULL,
 payload CLOB NOT NULL, payload_sha256 VARCHAR(64) NOT NULL,
 CONSTRAINT fk_adj2_receipt_revision FOREIGN KEY(case_id,work_version) REFERENCES adj2_case_revision(case_id,work_version),
 CONSTRAINT uq_adj2_receipt_version UNIQUE(case_id,work_version)
);
CREATE TABLE adj2_case_command_lock(lock_id INTEGER PRIMARY KEY);
INSERT INTO adj2_case_command_lock VALUES(0);
INSERT INTO adj2_case_command_lock VALUES(1);
INSERT INTO adj2_case_command_lock VALUES(2);
INSERT INTO adj2_case_command_lock VALUES(3);
INSERT INTO adj2_case_command_lock VALUES(4);
INSERT INTO adj2_case_command_lock VALUES(5);
INSERT INTO adj2_case_command_lock VALUES(6);
INSERT INTO adj2_case_command_lock VALUES(7);
