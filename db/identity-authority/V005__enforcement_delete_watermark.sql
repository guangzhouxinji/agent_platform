CREATE TABLE enf3_delete_watermark (
    source_id VARCHAR(120) PRIMARY KEY,
    source_epoch BIGINT NOT NULL,
    delete_epoch BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    valid_until TIMESTAMP NOT NULL,
    CONSTRAINT ck_enf3_delete_source_epoch CHECK (source_epoch > 0),
    CONSTRAINT ck_enf3_delete_epoch CHECK (delete_epoch >= 0),
    CONSTRAINT ck_enf3_delete_state CHECK (state IN ('READY','DELETED'))
);
