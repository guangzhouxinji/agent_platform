CREATE TABLE enf3_source_watermark (
    source_id VARCHAR(120) NOT NULL PRIMARY KEY,
    source_epoch BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    CONSTRAINT ck_enf3_source_watermark_epoch CHECK (source_epoch > 0),
    CONSTRAINT ck_enf3_source_watermark_state CHECK (state IN ('READY','PENDING','WITHDRAWN'))
);
