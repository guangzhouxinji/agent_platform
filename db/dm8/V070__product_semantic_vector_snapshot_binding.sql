CREATE TABLE con4_document_vector_binding (
    source_id VARCHAR(200) NOT NULL,
    document_id VARCHAR(300) NOT NULL,
    document_version VARCHAR(200) NOT NULL,
    field_name VARCHAR(32) NOT NULL,
    model_version VARCHAR(200) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    raw_sha VARCHAR(64) NOT NULL,
    locator_ref VARCHAR(1000) NOT NULL,
    vector_sha VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (source_id, document_id, document_version, field_name, model_version, source_version),
    CONSTRAINT fk_con4_vector_binding_vector FOREIGN KEY
        (source_id, document_id, document_version, field_name, model_version)
        REFERENCES con4_document_vector
        (source_id, document_id, document_version, field_name, model_version),
    CONSTRAINT ck_con4_vector_binding_field
        CHECK (field_name IN ('TITLE','ABSTRACT','CLAIMS','DESCRIPTION'))
);

INSERT INTO con4_document_vector_binding (
    source_id, document_id, document_version, field_name, model_version,
    source_version, raw_sha, locator_ref, vector_sha, created_at)
SELECT source_id, document_id, document_version, field_name, model_version,
       source_version, raw_sha, locator_ref, vector_sha, created_at
FROM con4_document_vector;

CREATE INDEX ix_con4_vector_binding_snapshot
    ON con4_document_vector_binding(model_version, source_id, source_version, field_name);
