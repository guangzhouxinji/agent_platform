ALTER TABLE enf_local_authorization ADD retention_policy VARCHAR(80) DEFAULT 'FIXED_UNTIL' NOT NULL;
ALTER TABLE enf_local_authorization MODIFY retention_until NULL;
ALTER TABLE enf_local_authorization ADD CONSTRAINT ck_enf_local_authorization_retention
    CHECK (retention_policy IN ('FIXED_UNTIL','UNTIL_DEVELOPMENT_PILOT_END'));
