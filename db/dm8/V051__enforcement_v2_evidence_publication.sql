CREATE TABLE enf4_evidence_publication (
    rule_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    basis_version VARCHAR(120) NOT NULL, scope_ref VARCHAR(300) NOT NULL,
    review_ref VARCHAR(300) NOT NULL, review_digest VARCHAR(64) NOT NULL,
    reviewed_by VARCHAR(200) NOT NULL, created_at TIMESTAMP NOT NULL,
    PRIMARY KEY(rule_id, revision),
    CONSTRAINT fk_enf4_publication_rule FOREIGN KEY(rule_id,revision)
        REFERENCES enf4_evidence_rule(rule_id,revision)
);

CREATE TABLE enf4_evidence_disabled (
    rule_id VARCHAR(120) NOT NULL, revision BIGINT NOT NULL,
    disabled_at TIMESTAMP NOT NULL,
    PRIMARY KEY(rule_id, revision),
    CONSTRAINT fk_enf4_disabled_rule FOREIGN KEY(rule_id,revision)
        REFERENCES enf4_evidence_publication(rule_id,revision)
);
