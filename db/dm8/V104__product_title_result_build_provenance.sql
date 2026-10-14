CREATE TABLE con4_result_title_build (
    result_set_id VARCHAR(36) NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    contract_version VARCHAR(200) NOT NULL,
    decision_id VARCHAR(36) NOT NULL,
    source_epoch BIGINT NOT NULL,
    model_version VARCHAR(300) NOT NULL,
    build_sha VARCHAR(64) NOT NULL,
    document_count INTEGER NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    record_sha VARCHAR(64) NOT NULL,
    PRIMARY KEY (result_set_id,source_id),
    CONSTRAINT fk_con4_title_build_result FOREIGN KEY (result_set_id) REFERENCES con4_result_set(result_set_id),
    CONSTRAINT ck_con4_title_build_epoch CHECK (source_epoch >= 1),
    CONSTRAINT ck_con4_title_build_count CHECK (document_count > 0)
);
