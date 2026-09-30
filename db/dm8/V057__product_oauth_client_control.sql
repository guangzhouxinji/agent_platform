CREATE TABLE id2_client_current (
    client_id VARCHAR(100) NOT NULL PRIMARY KEY,
    registered_id VARCHAR(100) NOT NULL UNIQUE,
    redirect_uri VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_id2_client_registered FOREIGN KEY (registered_id)
        REFERENCES oauth2_registered_client(id),
    CONSTRAINT ck_id2_client_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT ck_id2_client_version CHECK (version > 0)
);

CREATE TABLE id2_client_event (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    actor_ref VARCHAR(300) NOT NULL,
    request_sha VARCHAR(64) NOT NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_id2_client_event_current FOREIGN KEY (client_id)
        REFERENCES id2_client_current(client_id),
    CONSTRAINT ck_id2_client_operation CHECK (operation IN ('REGISTER','REVOKE'))
);

CREATE TABLE id2_client_request (
    request_id VARCHAR(180) NOT NULL PRIMARY KEY,
    request_sha VARCHAR(64) NOT NULL,
    event_id VARCHAR(36) NOT NULL UNIQUE,
    CONSTRAINT fk_id2_client_request_event FOREIGN KEY (event_id)
        REFERENCES id2_client_event(event_id)
);
