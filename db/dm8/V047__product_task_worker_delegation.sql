CREATE TABLE con3_worker_delegation (
    delegation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(200) NOT NULL,
    data_scope VARCHAR(300) NOT NULL,
    authorization_version BIGINT NOT NULL,
    human_issuer VARCHAR(300) NOT NULL,
    human_subject VARCHAR(200) NOT NULL,
    service_issuer VARCHAR(300) NOT NULL,
    service_subject VARCHAR(200) NOT NULL,
    human_grant_id VARCHAR(36) NOT NULL,
    service_grant_id VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con3_delegate_task FOREIGN KEY (task_id) REFERENCES con3_task(task_id),
    CONSTRAINT fk_con3_delegate_human FOREIGN KEY (human_issuer,human_subject)
        REFERENCES id2_subject(issuer,subject_id),
    CONSTRAINT fk_con3_delegate_service FOREIGN KEY (service_issuer,service_subject)
        REFERENCES id2_subject(issuer,subject_id),
    CONSTRAINT fk_con3_delegate_human_grant FOREIGN KEY (human_grant_id) REFERENCES id2_grant(grant_id),
    CONSTRAINT fk_con3_delegate_service_grant FOREIGN KEY (service_grant_id) REFERENCES id2_grant(grant_id),
    CONSTRAINT ck_con3_delegate_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT ck_con3_delegate_version CHECK (version > 0 AND authorization_version > 0
        AND expires_at > issued_at)
);

CREATE TABLE con3_worker_delegation_event (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    delegation_id VARCHAR(36) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    actor_ref VARCHAR(200) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con3_delegate_event FOREIGN KEY (delegation_id)
        REFERENCES con3_worker_delegation(delegation_id),
    CONSTRAINT ck_con3_delegate_event_operation CHECK (operation IN ('ISSUE','REVOKE')),
    CONSTRAINT ck_con3_delegate_event_version CHECK (after_version = before_version + 1)
);

CREATE TABLE con3_worker_delegation_receipt (
    request_id VARCHAR(100) NOT NULL PRIMARY KEY,
    request_sha CHAR(64) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    delegation_id VARCHAR(36) NOT NULL,
    result_version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con3_delegate_receipt FOREIGN KEY (delegation_id)
        REFERENCES con3_worker_delegation(delegation_id),
    CONSTRAINT ck_con3_delegate_receipt_operation CHECK (operation IN ('ISSUE','REVOKE'))
);
