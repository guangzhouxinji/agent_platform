-- Keep a validated constraint in place throughout non-transactional DDL.
ALTER TABLE con4_source_field_use ADD CONSTRAINT ck_con4_field_use_expand CHECK (use_code IN
    ('QUERY','SAVE','INDEX','DISPLAY','DOWNLOAD','MODEL','SHARE','COMPARISON'));
ALTER TABLE con4_source_field_use DROP CONSTRAINT ck_con4_field_use;
ALTER TABLE con4_source_field_use ADD CONSTRAINT ck_con4_field_use CHECK (use_code IN
    ('QUERY','SAVE','INDEX','DISPLAY','DOWNLOAD','MODEL','SHARE','COMPARISON'));
ALTER TABLE con4_source_field_use DROP CONSTRAINT ck_con4_field_use_expand;
