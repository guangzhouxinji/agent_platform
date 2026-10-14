CREATE TABLE con4_source_capture_identity (
    source_id VARCHAR(200) NOT NULL,
    source_version VARCHAR(100) NOT NULL,
    declaration_json CLOB NOT NULL,
    CONSTRAINT pk_con4_capture_identity PRIMARY KEY (source_id, source_version)
);

INSERT INTO con4_source_capture_identity(source_id,source_version,declaration_json)
    SELECT source_id,source_version,declaration_json FROM con4_source_contract;

ALTER TABLE con4_source_contract DROP CONSTRAINT uq_con4_source_version;
