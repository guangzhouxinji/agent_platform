ALTER TABLE con6_human_package ADD body_access_until TIMESTAMP;
ALTER TABLE con6_human_package ADD CONSTRAINT ck_con6_body_access
    CHECK (body_access_until IS NULL OR (body_access_until > created_at AND body_access_until <= body_until));
