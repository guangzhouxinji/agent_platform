CREATE TABLE con_model_configuration (
    model_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(256) NOT NULL,
    endpoint VARCHAR(1024) NOT NULL,
    encrypted_api_key VARCHAR(4096) NOT NULL,
    encryption_nonce VARCHAR(128) NOT NULL,
    enabled SMALLINT NOT NULL,
    priority_no INTEGER NOT NULL,
    data_channel VARCHAR(64) NOT NULL,
    change_note VARCHAR(512) NOT NULL,
    updated_by VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    last_test_status VARCHAR(64),
    last_tested_at TIMESTAMP,
    last_test_error_code VARCHAR(128),
    CONSTRAINT pk_con_model_configuration PRIMARY KEY (model_id),
    CONSTRAINT ck_con_model_configuration_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_con_model_configuration_priority CHECK (priority_no > 0),
    CONSTRAINT ck_con_model_configuration_channel CHECK (data_channel = 'PUBLIC_LOCAL')
);
CREATE UNIQUE INDEX ux_con_model_configuration_priority ON con_model_configuration(priority_no);

CREATE TABLE con_model_configuration_audit (
    audit_id VARCHAR(64) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(256) NOT NULL,
    endpoint VARCHAR(1024) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    enabled SMALLINT NOT NULL,
    priority_no INTEGER NOT NULL,
    test_status VARCHAR(64),
    test_error_code VARCHAR(128),
    change_note VARCHAR(512) NOT NULL,
    changed_by VARCHAR(256) NOT NULL,
    changed_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_con_model_configuration_audit PRIMARY KEY (audit_id),
    CONSTRAINT ck_con_model_configuration_audit_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_con_model_configuration_audit_action CHECK (action_type IN ('CREATED', 'UPDATED', 'REORDERED', 'TESTED'))
);
CREATE INDEX ix_con_model_configuration_audit_model ON con_model_configuration_audit(model_id, changed_at);
