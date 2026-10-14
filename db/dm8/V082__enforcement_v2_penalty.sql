CREATE TABLE enf11_penalty_revision (
  resource_kind VARCHAR(32) NOT NULL,
  resource_id VARCHAR(120) NOT NULL,
  revision BIGINT NOT NULL,
  organization_id VARCHAR(120) NOT NULL,
  state VARCHAR(32) NOT NULL,
  payload CLOB NOT NULL,
  artifact_key VARCHAR(160) NOT NULL,
  artifact_sha CHAR(64) NOT NULL,
  actor_ref VARCHAR(160) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY(resource_kind,resource_id,revision)
);
CREATE TABLE enf11_penalty_receipt (
  request_key VARCHAR(120) PRIMARY KEY,
  fingerprint CHAR(64) NOT NULL,
  actor_ref VARCHAR(160) NOT NULL,
  operation VARCHAR(48) NOT NULL,
  resource_kind VARCHAR(32) NOT NULL,
  resource_id VARCHAR(120) NOT NULL,
  revision BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL
);
CREATE TABLE enf11_penalty_event (
  event_id VARCHAR(36) PRIMARY KEY,
  request_key VARCHAR(120) NOT NULL UNIQUE,
  resource_kind VARCHAR(32) NOT NULL,
  resource_id VARCHAR(120) NOT NULL,
  revision BIGINT NOT NULL,
  actor_ref VARCHAR(160) NOT NULL,
  operation VARCHAR(48) NOT NULL,
  created_at TIMESTAMP NOT NULL
);
CREATE TABLE enf11_penalty_lock (bucket_id INTEGER PRIMARY KEY);
INSERT INTO enf11_penalty_lock SELECT LEVEL-1 FROM DUAL CONNECT BY LEVEL<=32;
