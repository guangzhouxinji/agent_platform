CREATE TABLE con4_source_verification (
    verification_id VARCHAR(36) NOT NULL PRIMARY KEY,
    source_id VARCHAR(200) NOT NULL,
    contract_version VARCHAR(100) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    candidate_row_version BIGINT NOT NULL,
    manifest_ref VARCHAR(500) NOT NULL,
    manifest_sha VARCHAR(64) NOT NULL,
    records_sha VARCHAR(64) NOT NULL,
    inventory_sha VARCHAR(64) NOT NULL,
    record_count BIGINT NOT NULL,
    raw_count BIGINT NOT NULL,
    verifier_ref VARCHAR(300) NOT NULL,
    verified_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_con4_verification_contract UNIQUE (source_id, contract_version),
    CONSTRAINT fk_con4_verification_contract FOREIGN KEY (source_id, contract_version)
        REFERENCES con4_source_contract(source_id, contract_version),
    CONSTRAINT ck_con4_verification_counts CHECK (candidate_row_version > 0
        AND record_count > 0 AND raw_count > 0)
);

CREATE TABLE con4_source_verification_request (
    request_id VARCHAR(200) NOT NULL PRIMARY KEY,
    request_sha VARCHAR(64) NOT NULL,
    verification_id VARCHAR(36) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con4_verification_request FOREIGN KEY (verification_id)
        REFERENCES con4_source_verification(verification_id)
);
