ALTER TABLE con4_result_set ADD confidence_status VARCHAR(32) DEFAULT 'NORMAL' NOT NULL;
ALTER TABLE con4_result_set ADD confidence_rule_version VARCHAR(80)
    DEFAULT 'RETRIEVAL_CONFIDENCE/v1' NOT NULL;
ALTER TABLE con4_result_set ADD confidence_reason VARCHAR(120)
    DEFAULT 'LEGACY_RESULT_NOT_ASSESSED' NOT NULL;
ALTER TABLE con4_result_set ADD CONSTRAINT ck_con4_result_confidence
    CHECK (confidence_status IN ('NO_RESULT','LOW_CONFIDENCE','NORMAL'));
