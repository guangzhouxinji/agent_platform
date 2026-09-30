CREATE TABLE id4_source_recovery_event (
    recovery_id VARCHAR(36) NOT NULL PRIMARY KEY,
    source_id VARCHAR(200) NOT NULL,
    intent_id VARCHAR(36) NOT NULL,
    operator_ref VARCHAR(300) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    before_epoch BIGINT NOT NULL,
    after_epoch BIGINT NOT NULL,
    before_history_sha VARCHAR(64) NOT NULL,
    after_history_sha VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_id4_recovery_outcome CHECK (outcome IN ('S1_RELEASE','S2_ADVANCE')),
    CONSTRAINT ck_id4_recovery_epoch CHECK (before_epoch >= 0 AND after_epoch >= before_epoch)
);
