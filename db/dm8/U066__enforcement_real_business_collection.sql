DROP TABLE enf8_real_business_task_event;
DROP TABLE enf8_real_business_task;
ALTER TABLE enf8_job_pack_revision DROP CONSTRAINT ck_enf8_pack_state;
ALTER TABLE enf8_job_pack_revision MODIFY state VARCHAR(24);
ALTER TABLE enf8_job_pack_receipt MODIFY state VARCHAR(24);
ALTER TABLE enf8_job_pack_event MODIFY state VARCHAR(24);
ALTER TABLE enf8_job_pack_revision ADD CONSTRAINT ck_enf8_pack_state CHECK(state IN (
    'COLLECTING','WAITING_DATA','READY','RUNNING','PASSED','FAILED'));
