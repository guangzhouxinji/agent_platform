DROP TABLE id2_workload_request;
DROP TABLE id2_workload_event;
DROP TABLE id2_workload;
ALTER TABLE id2_subject_event DROP CONSTRAINT ck_id2_subject_event_operation;
ALTER TABLE id2_subject_event ADD CONSTRAINT ck_id2_subject_event_operation
    CHECK (operation IN ('REGISTER','ACTIVATE','DISABLE'));
ALTER TABLE id2_subject DROP CONSTRAINT ck_id2_subject_kind;
ALTER TABLE id2_subject DROP CONSTRAINT ck_id2_subject_activation;
ALTER TABLE id2_subject ADD CONSTRAINT ck_id2_subject_activation CHECK (
    (status = 'PENDING' AND password_hash IS NULL AND activation_sha IS NOT NULL
        AND activation_expires_at IS NOT NULL)
    OR (status = 'ACTIVE' AND password_hash IS NOT NULL
        AND activation_sha IS NULL AND activation_expires_at IS NULL)
    OR (status = 'DISABLED' AND activation_sha IS NULL AND activation_expires_at IS NULL)
);
ALTER TABLE id2_subject DROP COLUMN subject_kind;
