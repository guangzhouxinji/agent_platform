CREATE TABLE enf4_organization (
    organization_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    owner_organization VARCHAR(200) NOT NULL, case_ref VARCHAR(120) NOT NULL,
    case_version VARCHAR(120) NOT NULL, rule_id VARCHAR(120) NOT NULL,
    rule_revision BIGINT NOT NULL, review_state VARCHAR(32) NOT NULL,
    payload CLOB NOT NULL, created_by VARCHAR(200) NOT NULL, created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(organization_id, revision),
    CONSTRAINT fk_enf4_org_rule FOREIGN KEY(rule_id, rule_revision)
        REFERENCES enf4_evidence_rule(rule_id, revision),
    CONSTRAINT ck_enf4_org_revision CHECK (revision > 0)
);

CREATE TABLE enf4_organization_receipt (
    request_id VARCHAR(120) PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(40) NOT NULL,
    organization_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE enf4_organization_event (
    event_id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(120) NOT NULL UNIQUE,
    organization_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    actor_ref VARCHAR(200) NOT NULL, operation VARCHAR(40) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_enf4_org_event_receipt FOREIGN KEY(request_id)
        REFERENCES enf4_organization_receipt(request_id)
);
