-- Refuse rollback BEFORE changing the active constraint if comparison decisions exist.
-- Do not delete, translate, or conceal those historical decisions.
ALTER TABLE con4_source_field_use ADD CONSTRAINT ck_con4_field_use_legacy CHECK (use_code IN
    ('QUERY','SAVE','INDEX','DISPLAY','DOWNLOAD','MODEL','SHARE'));
ALTER TABLE con4_source_field_use DROP CONSTRAINT ck_con4_field_use;
ALTER TABLE con4_source_field_use ADD CONSTRAINT ck_con4_field_use CHECK (use_code IN
    ('QUERY','SAVE','INDEX','DISPLAY','DOWNLOAD','MODEL','SHARE'));
ALTER TABLE con4_source_field_use DROP CONSTRAINT ck_con4_field_use_legacy;
