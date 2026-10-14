-- Refuse BEFORE any DROP if preparations or their FK-bound confirmation history exist.
ALTER TABLE con5_preparation ADD CONSTRAINT ck_con5_empty_rollback CHECK (1=0);
DROP TABLE con5_feature_event;
DROP TABLE con5_feature_current;
DROP TABLE con5_feature;
DROP TABLE con5_feature_revision;
DROP TABLE con5_prepared_document;
DROP TABLE con5_preparation;
