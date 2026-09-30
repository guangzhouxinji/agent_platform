CREATE TABLE id4_source_watermark (
    source_id VARCHAR(200) NOT NULL PRIMARY KEY,
    source_epoch BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    intent_id VARCHAR(36),
    history_sha VARCHAR(64) NOT NULL,
    CONSTRAINT ck_id4_source_epoch CHECK (source_epoch >= 0),
    CONSTRAINT ck_id4_source_state CHECK (
        (state = 'PENDING' AND intent_id IS NOT NULL) OR
        (state IN ('READY','WITHDRAWN') AND intent_id IS NULL))
);
