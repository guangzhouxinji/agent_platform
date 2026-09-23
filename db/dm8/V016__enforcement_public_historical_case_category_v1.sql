ALTER TABLE enf_public_reference_snapshot DROP CONSTRAINT ck_enf_public_reference_category;
ALTER TABLE enf_public_reference_snapshot ADD CONSTRAINT ck_enf_public_reference_category CHECK (
    category IN ('PUBLIC_LEAD_CANDIDATE','PUBLIC_RIGHT_STATUS_SNAPSHOT','PUBLIC_LEGAL_REFERENCE','PUBLIC_HISTORICAL_CASE_SAMPLE')
);
