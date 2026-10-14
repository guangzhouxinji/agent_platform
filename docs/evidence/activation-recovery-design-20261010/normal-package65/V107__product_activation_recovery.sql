ALTER TABLE id2_subject_event DROP CONSTRAINT ck_id2_subject_event_operation;
ALTER TABLE id2_subject_event ADD CONSTRAINT ck_id2_subject_event_operation
 CHECK (operation IN ('REGISTER','ACTIVATE','DISABLE','SERVICE_REGISTER','SERVICE_DISABLE','REISSUE'));
CREATE TABLE id2_activation_recovery_receipt (
 issuer VARCHAR(300) NOT NULL,
 actor_ref VARCHAR(200) NOT NULL,
 request_id VARCHAR(36) NOT NULL,
 subject_id VARCHAR(200) NOT NULL,
 expected_version BIGINT NOT NULL,
 result_version BIGINT NOT NULL,
 activation_expires_at TIMESTAMP NOT NULL,
 occurred_at TIMESTAMP NOT NULL,
 CONSTRAINT pk_id2_activation_recovery PRIMARY KEY (issuer,actor_ref,request_id),
 CONSTRAINT fk_id2_activation_subject FOREIGN KEY (issuer,subject_id) REFERENCES id2_subject(issuer,subject_id),
 CONSTRAINT ck_id2_activation_versions CHECK (expected_version >= 1 AND result_version=expected_version+1)
);
