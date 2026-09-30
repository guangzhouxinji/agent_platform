-- Install in an independently backed-up database, never in the application DM8 backup set.
CREATE TABLE id2_authority_watermark (
    issuer VARCHAR(300) NOT NULL PRIMARY KEY,
    generation BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    intent_id VARCHAR(100),
    history_sha CHAR(64) NOT NULL,
    CONSTRAINT ck_id2_authority_generation CHECK (generation >= 0),
    CONSTRAINT ck_id2_authority_state CHECK (
        (state = 'READY' AND intent_id IS NULL) OR
        (state = 'PENDING' AND intent_id IS NOT NULL))
);

CREATE TABLE id2_maintainer (
    singleton_key INTEGER NOT NULL PRIMARY KEY,
    subject_ref VARCHAR(200) NOT NULL,
    organization VARCHAR(200) NOT NULL,
    certificate_sha CHAR(64) NOT NULL,
    allowed_roles VARCHAR(80) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT ck_id2_maintainer_single CHECK (singleton_key = 1),
    CONSTRAINT ck_id2_maintainer_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT ck_id2_maintainer_version CHECK (version >= 1)
);

CREATE TABLE id2_maintenance_audit (
    audit_id VARCHAR(36) NOT NULL PRIMARY KEY,
    subject_ref VARCHAR(200),
    certificate_sha CHAR(64),
    role_code VARCHAR(16) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_id2_maintenance_decision CHECK (decision IN ('BOOTSTRAP','ALLOW','DENY','REVOKE'))
);
