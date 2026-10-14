-- Refuses safely if renewed contracts share a capture version; never delete history to force rollback.
ALTER TABLE con4_source_contract ADD CONSTRAINT uq_con4_source_version UNIQUE (source_id,source_version);
DROP TABLE con4_source_capture_identity;
