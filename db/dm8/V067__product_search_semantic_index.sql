CREATE TABLE con4_embedding_model (
    model_version VARCHAR(200) NOT NULL PRIMARY KEY,
    provider VARCHAR(100) NOT NULL,
    model_id VARCHAR(300) NOT NULL,
    model_revision VARCHAR(100) NOT NULL,
    model_sha VARCHAR(64) NOT NULL,
    tokenizer_sha VARCHAR(64) NOT NULL,
    vector_dimension INT NOT NULL,
    max_tokens INT NOT NULL,
    normalization VARCHAR(100) NOT NULL,
    license_id VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_con4_embedding_dimension CHECK (vector_dimension > 0 AND max_tokens > 0),
    CONSTRAINT ck_con4_embedding_status CHECK (status IN ('ACTIVE','RETIRED'))
);

CREATE TABLE con4_document_vector (
    source_id VARCHAR(200) NOT NULL,
    document_id VARCHAR(300) NOT NULL,
    document_version VARCHAR(200) NOT NULL,
    field_name VARCHAR(32) NOT NULL,
    model_version VARCHAR(200) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    raw_sha VARCHAR(64) NOT NULL,
    locator_ref VARCHAR(1000) NOT NULL,
    vector_sha VARCHAR(64) NOT NULL,
    vector_data BLOB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (source_id, document_id, document_version, field_name, model_version),
    CONSTRAINT fk_con4_vector_model FOREIGN KEY (model_version)
        REFERENCES con4_embedding_model(model_version),
    CONSTRAINT ck_con4_vector_field CHECK (field_name IN ('TITLE','ABSTRACT','CLAIMS','DESCRIPTION'))
);

CREATE TABLE con4_vector_build (
    build_id VARCHAR(36) NOT NULL PRIMARY KEY,
    request_id VARCHAR(200) NOT NULL UNIQUE,
    request_sha VARCHAR(64) NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    contract_version VARCHAR(100) NOT NULL,
    decision_id VARCHAR(36) NOT NULL,
    source_epoch BIGINT NOT NULL,
    field_name VARCHAR(32) NOT NULL,
    model_version VARCHAR(200) NOT NULL,
    document_count INT NOT NULL,
    embedded_count INT NOT NULL,
    reused_count INT NOT NULL,
    build_sha VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(300) NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con4_vector_build_decision FOREIGN KEY (decision_id, source_id, contract_version)
        REFERENCES con4_source_admission(decision_id, source_id, contract_version),
    CONSTRAINT fk_con4_vector_build_model FOREIGN KEY (model_version)
        REFERENCES con4_embedding_model(model_version),
    CONSTRAINT ck_con4_vector_build_counts CHECK (source_epoch > 0 AND document_count > 0
        AND embedded_count >= 0 AND reused_count >= 0
        AND embedded_count + reused_count = document_count),
    CONSTRAINT ck_con4_vector_build_field CHECK (field_name IN ('TITLE','ABSTRACT','CLAIMS','DESCRIPTION'))
);

CREATE INDEX ix_con4_vector_model_source ON con4_document_vector(model_version, source_id, field_name);
