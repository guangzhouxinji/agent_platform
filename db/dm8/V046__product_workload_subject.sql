ALTER TABLE id2_subject ADD subject_kind VARCHAR(16) DEFAULT 'HUMAN' NOT NULL;
ALTER TABLE id2_subject DROP CONSTRAINT ck_id2_subject_activation;
ALTER TABLE id2_subject ADD CONSTRAINT ck_id2_subject_activation CHECK (
    (subject_kind='HUMAN' AND (
        (status='PENDING' AND password_hash IS NULL AND activation_sha IS NOT NULL
            AND activation_expires_at IS NOT NULL)
        OR (status='ACTIVE' AND password_hash IS NOT NULL
            AND activation_sha IS NULL AND activation_expires_at IS NULL)
        OR (status='DISABLED' AND activation_sha IS NULL AND activation_expires_at IS NULL)))
    OR (subject_kind='SERVICE' AND status IN ('ACTIVE','DISABLED')
        AND password_hash IS NULL AND activation_sha IS NULL AND activation_expires_at IS NULL)
);
ALTER TABLE id2_subject ADD CONSTRAINT ck_id2_subject_kind
    CHECK (subject_kind IN ('HUMAN','SERVICE'));
ALTER TABLE id2_subject_event DROP CONSTRAINT ck_id2_subject_event_operation;
ALTER TABLE id2_subject_event ADD CONSTRAINT ck_id2_subject_event_operation
    CHECK (operation IN ('REGISTER','ACTIVATE','DISABLE','SERVICE_REGISTER','SERVICE_DISABLE'));

CREATE TABLE id2_workload (
    issuer VARCHAR(300) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    certificate_sha VARCHAR(64) NOT NULL,
    organization VARCHAR(200) NOT NULL,
    workload_kind VARCHAR(24) NOT NULL,
    allowed_purpose VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (issuer,subject_id),
    CONSTRAINT uq_id2_workload_certificate UNIQUE (issuer,certificate_sha),
    CONSTRAINT fk_id2_workload_subject FOREIGN KEY (issuer,subject_id)
        REFERENCES id2_subject(issuer,subject_id),
    CONSTRAINT ck_id2_workload_kind CHECK (workload_kind IN ('AGENT','SEARCH_JOB','FILE_JOB')),
    CONSTRAINT ck_id2_workload_purpose CHECK (allowed_purpose = 'TASK_WORK'),
    CONSTRAINT ck_id2_workload_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT ck_id2_workload_version CHECK (version > 0 AND expires_at > created_at)
);

CREATE TABLE id2_workload_event (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    issuer VARCHAR(300) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    maintainer_ref VARCHAR(200) NOT NULL,
    certificate_sha VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_id2_workload_event FOREIGN KEY (issuer,subject_id)
        REFERENCES id2_workload(issuer,subject_id),
    CONSTRAINT ck_id2_workload_event_operation CHECK (operation IN ('REGISTER','REVOKE')),
    CONSTRAINT ck_id2_workload_event_version CHECK (after_version = before_version + 1)
);

CREATE TABLE id2_workload_request (
    request_id VARCHAR(100) NOT NULL PRIMARY KEY,
    request_sha VARCHAR(64) NOT NULL,
    issuer VARCHAR(300) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    result_version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_id2_workload_request FOREIGN KEY (issuer,subject_id)
        REFERENCES id2_workload(issuer,subject_id),
    CONSTRAINT ck_id2_workload_request_operation CHECK (operation IN ('REGISTER','REVOKE'))
);
