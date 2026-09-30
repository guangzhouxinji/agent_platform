CREATE TABLE id2_subject (
    issuer VARCHAR(300) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    login_name VARCHAR(200) NOT NULL,
    password_hash VARCHAR(200),
    activation_sha CHAR(64),
    activation_expires_at TIMESTAMP,
    status VARCHAR(16) NOT NULL,
    credential_version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    disabled_at TIMESTAMP,
    CONSTRAINT pk_id2_subject PRIMARY KEY (issuer, subject_id),
    CONSTRAINT uq_id2_subject_login UNIQUE (issuer, login_name),
    CONSTRAINT ck_id2_subject_status CHECK (status IN ('PENDING','ACTIVE','DISABLED')),
    CONSTRAINT ck_id2_subject_activation CHECK (
        (status = 'PENDING' AND password_hash IS NULL AND activation_sha IS NOT NULL
            AND activation_expires_at IS NOT NULL)
        OR (status = 'ACTIVE' AND password_hash IS NOT NULL
            AND activation_sha IS NULL AND activation_expires_at IS NULL)
        OR (status = 'DISABLED' AND activation_sha IS NULL AND activation_expires_at IS NULL)),
    CONSTRAINT ck_id2_subject_version CHECK (credential_version >= 1)
);

CREATE TABLE id2_subject_event (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    issuer VARCHAR(300) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    actor_ref VARCHAR(200) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_id2_subject_event_subject FOREIGN KEY (issuer,subject_id)
        REFERENCES id2_subject(issuer,subject_id),
    CONSTRAINT ck_id2_subject_event_operation CHECK (operation IN ('REGISTER','ACTIVATE','DISABLE')),
    CONSTRAINT ck_id2_subject_event_version CHECK (after_version = before_version + 1)
);

CREATE TABLE id2_key_control (
    issuer VARCHAR(300) NOT NULL,
    active_kid VARCHAR(100),
    version BIGINT NOT NULL,
    CONSTRAINT pk_id2_key_control PRIMARY KEY (issuer),
    CONSTRAINT ck_id2_key_control_version CHECK (version >= 0)
);

CREATE TABLE id2_signing_key (
    kid VARCHAR(100) NOT NULL,
    issuer VARCHAR(300) NOT NULL,
    public_spki BLOB NOT NULL,
    private_pkcs8_encrypted BLOB NOT NULL,
    nonce BINARY(12) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    verify_until TIMESTAMP,
    CONSTRAINT pk_id2_signing_key PRIMARY KEY (kid),
    CONSTRAINT fk_id2_key_issuer FOREIGN KEY (issuer) REFERENCES id2_key_control(issuer),
    CONSTRAINT ck_id2_key_status CHECK (status IN ('ACTIVE','VERIFY_ONLY','REVOKED'))
);
CREATE INDEX ix_id2_key_issuer ON id2_signing_key(issuer, status);

CREATE TABLE id2_key_event (
    event_id VARCHAR(36) NOT NULL,
    issuer VARCHAR(300) NOT NULL,
    kid VARCHAR(100) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    maintainer_ref VARCHAR(200) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_id2_key_event PRIMARY KEY (event_id),
    CONSTRAINT fk_id2_key_event_key FOREIGN KEY (kid) REFERENCES id2_signing_key(kid),
    CONSTRAINT ck_id2_key_event_operation CHECK (operation IN ('ROTATE','REVOKE')),
    CONSTRAINT ck_id2_key_event_version CHECK (after_version = before_version + 1)
);

CREATE TABLE id2_session (
    sid VARCHAR(100) NOT NULL,
    jti VARCHAR(100) NOT NULL,
    issuer VARCHAR(300) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    client_id VARCHAR(200) NOT NULL,
    kid VARCHAR(100) NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    revoked_at TIMESTAMP,
    CONSTRAINT pk_id2_session PRIMARY KEY (sid),
    CONSTRAINT uq_id2_session_jti UNIQUE (jti),
    CONSTRAINT fk_id2_session_subject FOREIGN KEY (issuer, subject_id) REFERENCES id2_subject(issuer, subject_id),
    CONSTRAINT fk_id2_session_key FOREIGN KEY (kid) REFERENCES id2_signing_key(kid),
    CONSTRAINT ck_id2_session_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT ck_id2_session_times CHECK (issued_at < expires_at)
);
CREATE INDEX ix_id2_session_subject ON id2_session(issuer, subject_id, status, expires_at);

CREATE TABLE id2_grant (
    grant_id VARCHAR(36) NOT NULL,
    issuer VARCHAR(300) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    organization VARCHAR(200) NOT NULL,
    domain_code VARCHAR(40) NOT NULL,
    task_scope VARCHAR(200) NOT NULL,
    data_scope VARCHAR(300) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id VARCHAR(200) NOT NULL,
    resource_version BIGINT NOT NULL,
    action_code VARCHAR(40) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    grant_generation BIGINT NOT NULL,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    status VARCHAR(16) NOT NULL,
    grant_version BIGINT NOT NULL,
    CONSTRAINT pk_id2_grant PRIMARY KEY (grant_id),
    CONSTRAINT fk_id2_grant_subject FOREIGN KEY (issuer, subject_id) REFERENCES id2_subject(issuer, subject_id),
    CONSTRAINT uq_id2_grant_generation UNIQUE (issuer, subject_id, organization, domain_code, resource_type,
        resource_id, resource_version, action_code, purpose, grant_generation),
    CONSTRAINT ck_id2_grant_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT ck_id2_grant_version CHECK (resource_version >= 1 AND grant_generation >= 1 AND grant_version >= 1),
    CONSTRAINT ck_id2_grant_times CHECK (issued_at < expires_at)
);
CREATE INDEX ix_id2_grant_subject ON id2_grant(issuer, subject_id, organization, status, expires_at);

CREATE TABLE id2_current_epoch (
    issuer VARCHAR(300) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    organization VARCHAR(200) NOT NULL,
    domain_code VARCHAR(40) NOT NULL,
    resource_type VARCHAR(40) NOT NULL,
    resource_id VARCHAR(200) NOT NULL,
    action_code VARCHAR(40) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    authorization_epoch BIGINT NOT NULL,
    source_epoch BIGINT NOT NULL,
    current_grant_id VARCHAR(36),
    CONSTRAINT pk_id2_current_epoch PRIMARY KEY (issuer, subject_id, organization, domain_code, resource_type,
        resource_id, action_code, purpose),
    CONSTRAINT fk_id2_epoch_grant FOREIGN KEY (current_grant_id) REFERENCES id2_grant(grant_id),
    CONSTRAINT ck_id2_epochs CHECK (authorization_epoch >= 1 AND source_epoch >= 0)
);

CREATE TABLE id2_grant_event (
    event_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    grant_id VARCHAR(36) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    after_epoch BIGINT NOT NULL,
    maintainer_ref VARCHAR(200) NOT NULL,
    request_sha CHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_id2_grant_event PRIMARY KEY (event_id),
    CONSTRAINT uq_id2_grant_event_request UNIQUE (request_id),
    CONSTRAINT fk_id2_event_grant FOREIGN KEY (grant_id) REFERENCES id2_grant(grant_id),
    CONSTRAINT ck_id2_event_operation CHECK (operation IN ('ISSUE','SHORTEN','REVOKE')),
    CONSTRAINT ck_id2_event_version CHECK (after_version = before_version + 1)
);
CREATE INDEX ix_id2_event_grant ON id2_grant_event(grant_id, after_version);
