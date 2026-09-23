CREATE TABLE enf_source_configuration (
    source_id VARCHAR(128) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    source_name VARCHAR(256) NOT NULL,
    data_type VARCHAR(64) NOT NULL,
    access_method VARCHAR(64) NOT NULL,
    controlled_endpoint VARCHAR(512) NOT NULL,
    credential_ref VARCHAR(256) NOT NULL,
    field_mapping_json CLOB NOT NULL,
    owner_name VARCHAR(256) NOT NULL,
    schedule_mode VARCHAR(32) NOT NULL,
    schedule_interval INTEGER,
    schedule_unit VARCHAR(16),
    enabled SMALLINT NOT NULL,
    health_status VARCHAR(32) NOT NULL,
    last_success_cursor VARCHAR(256),
    last_success_at TIMESTAMP,
    active_task_id VARCHAR(64),
    version_no INTEGER NOT NULL,
    created_by VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(256) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_source_configuration PRIMARY KEY (source_id),
    CONSTRAINT ck_enf_source_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_enf_source_schedule_mode CHECK (schedule_mode IN ('MANUAL', 'SCHEDULED')),
    CONSTRAINT ck_enf_source_schedule_unit CHECK (schedule_unit IS NULL OR schedule_unit IN ('MINUTES', 'HOURS')),
    CONSTRAINT ck_enf_source_health CHECK (health_status IN ('NOT_TESTED', 'HEALTHY', 'UNHEALTHY')),
    CONSTRAINT ck_enf_source_version CHECK (version_no > 0)
);
CREATE INDEX ix_enf_source_configuration_org ON enf_source_configuration(organization_ref, updated_at);

CREATE TABLE enf_source_configuration_history (
    history_id VARCHAR(64) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    version_no INTEGER NOT NULL,
    configuration_json CLOB NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    changed_by VARCHAR(256) NOT NULL,
    changed_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_source_configuration_history PRIMARY KEY (history_id),
    CONSTRAINT uq_enf_source_configuration_history UNIQUE (source_id, version_no)
);

CREATE TABLE enf_source_connection_test (
    test_id VARCHAR(64) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    configuration_version INTEGER NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    authentication_status VARCHAR(32) NOT NULL,
    pagination_status VARCHAR(32) NOT NULL,
    time_field_status VARCHAR(32) NOT NULL,
    unique_key_status VARCHAR(32) NOT NULL,
    response_shape_status VARCHAR(32) NOT NULL,
    error_code VARCHAR(128),
    tested_by VARCHAR(256) NOT NULL,
    tested_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_source_connection_test PRIMARY KEY (test_id)
);
CREATE INDEX ix_enf_source_connection_test_source ON enf_source_connection_test(source_id, tested_at);

CREATE TABLE enf_source_sync_task (
    task_id VARCHAR(64) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    configuration_version INTEGER NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    processed_count INTEGER NOT NULL,
    imported_count INTEGER NOT NULL,
    failed_count INTEGER NOT NULL,
    start_cursor VARCHAR(256),
    result_cursor VARCHAR(256),
    error_code VARCHAR(128),
    started_by VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_source_sync_task PRIMARY KEY (task_id),
    CONSTRAINT uq_enf_source_sync_idempotency UNIQUE (organization_ref, source_id, idempotency_key),
    CONSTRAINT ck_enf_source_sync_status CHECK (status IN ('queued', 'running', 'succeeded', 'partial_failed', 'failed', 'cancelled'))
);
CREATE INDEX ix_enf_source_sync_task_source ON enf_source_sync_task(source_id, created_at);

CREATE TABLE enf_source_sync_failure (
    failure_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    sequence_no INTEGER NOT NULL,
    source_record_ref VARCHAR(256),
    error_code VARCHAR(128) NOT NULL,
    error_message VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_source_sync_failure PRIMARY KEY (failure_id)
);
CREATE INDEX ix_enf_source_sync_failure_task ON enf_source_sync_failure(task_id, sequence_no);

CREATE TABLE enf_source_record_snapshot (
    snapshot_id VARCHAR(64) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    source_record_id VARCHAR(256) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    source_updated_at TIMESTAMP NOT NULL,
    fetched_at TIMESTAMP NOT NULL,
    payload_json CLOB NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    withdrawal_status VARCHAR(32) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    CONSTRAINT pk_enf_source_record_snapshot PRIMARY KEY (snapshot_id),
    CONSTRAINT uq_enf_source_record_snapshot UNIQUE (source_id, source_record_id, source_version),
    CONSTRAINT ck_enf_source_record_withdrawal CHECK (withdrawal_status IN ('AVAILABLE', 'WITHDRAWN'))
);
CREATE INDEX ix_enf_source_record_snapshot_source ON enf_source_record_snapshot(source_id, source_record_id, fetched_at);
