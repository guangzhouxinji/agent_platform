-- Apply only to independent authority, never restore alongside business working snapshots.
CREATE TABLE adj2_source_generation (singleton_key INTEGER PRIMARY KEY, generation BIGINT NOT NULL, state VARCHAR(16) NOT NULL,
 CONSTRAINT ck_adj2_source_generation CHECK(state IN('READY','PENDING')));
INSERT INTO adj2_source_generation VALUES(1,0,'READY');
CREATE TABLE adj2_source_use (
 source_key VARCHAR(64) PRIMARY KEY, case_id VARCHAR(120) NOT NULL, organization VARCHAR(200) NOT NULL,
 data_scope VARCHAR(200) NOT NULL, payload CLOB NOT NULL, payload_sha256 VARCHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL, version BIGINT NOT NULL, maintainer_ref VARCHAR(600) NOT NULL,
 CONSTRAINT ck_adj2_source_use CHECK(state IN('ACTIVE','REVOKED') AND version>=1)
);
CREATE TABLE adj2_source_event (
 event_id VARCHAR(36) PRIMARY KEY, source_key VARCHAR(64) NOT NULL, version BIGINT NOT NULL,
 action_code VARCHAR(16) NOT NULL, maintainer_ref VARCHAR(600) NOT NULL, occurred_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_adj2_source_event FOREIGN KEY(source_key) REFERENCES adj2_source_use(source_key),
 CONSTRAINT uq_adj2_source_event UNIQUE(source_key,version),
 CONSTRAINT ck_adj2_source_event CHECK(action_code IN('REGISTER','REVOKE'))
);
