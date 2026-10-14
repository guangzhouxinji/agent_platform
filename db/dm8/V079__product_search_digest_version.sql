ALTER TABLE con4_result_set ADD digest_version VARCHAR(100) DEFAULT 'RESULT_DIGEST_PRE_STORAGE/v1' NOT NULL;
ALTER TABLE con4_result_set ADD CONSTRAINT ck_con4_result_digest_version
    CHECK (digest_version IN ('RESULT_DIGEST_PRE_STORAGE/v1','RESULT_DIGEST_DECIMAL_12/v2'));
