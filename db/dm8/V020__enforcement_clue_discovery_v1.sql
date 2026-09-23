CREATE TABLE enf_clue_discovery_task (
    task_id VARCHAR(64) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    requester_ref VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    input_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    processed_count INTEGER NOT NULL,
    matched_count INTEGER NOT NULL,
    lead_count INTEGER NOT NULL,
    failed_count INTEGER NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT pk_enf_clue_discovery_task PRIMARY KEY (task_id),
    CONSTRAINT uq_enf_clue_discovery_idem UNIQUE (organization_ref, requester_ref, idempotency_key),
    CONSTRAINT ck_enf_clue_discovery_status CHECK (status IN ('queued','running','succeeded','partial_failed','failed','cancelled'))
);
CREATE INDEX ix_enf_clue_discovery_task_org ON enf_clue_discovery_task(organization_ref, created_at);

CREATE TABLE enf_clue_discovery_input_sync (
    task_id VARCHAR(64) NOT NULL,
    sequence_no INTEGER NOT NULL,
    source_sync_task_id VARCHAR(64) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    source_configuration_version INTEGER NOT NULL,
    CONSTRAINT pk_enf_clue_discovery_input_sync PRIMARY KEY (task_id, sequence_no),
    CONSTRAINT uq_enf_clue_discovery_input_sync UNIQUE (task_id, source_sync_task_id)
);

CREATE TABLE enf_clue_discovery_input_snapshot (
    task_id VARCHAR(64) NOT NULL,
    sequence_no INTEGER NOT NULL,
    snapshot_id VARCHAR(64) NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    CONSTRAINT pk_enf_clue_input_snapshot PRIMARY KEY (task_id, sequence_no),
    CONSTRAINT uq_enf_clue_input_snapshot UNIQUE (task_id, snapshot_id)
);

CREATE TABLE enf_clue_discovery_input_rule (
    task_id VARCHAR(64) NOT NULL,
    sequence_no INTEGER NOT NULL,
    rule_id VARCHAR(128) NOT NULL,
    rule_version INTEGER NOT NULL,
    rule_revision INTEGER NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    CONSTRAINT pk_enf_clue_discovery_input_rule PRIMARY KEY (task_id, sequence_no),
    CONSTRAINT uq_enf_clue_discovery_input_rule UNIQUE (task_id, rule_id, rule_version)
);

CREATE TABLE enf_clue_discovery_item (
    item_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    sequence_no INTEGER NOT NULL,
    snapshot_id VARCHAR(64),
    source_id VARCHAR(128),
    source_record_id VARCHAR(256),
    source_version VARCHAR(128),
    rule_id VARCHAR(128),
    rule_version INTEGER,
    item_status VARCHAR(32) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    retryable SMALLINT NOT NULL,
    lead_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_clue_discovery_item PRIMARY KEY (item_id),
    CONSTRAINT ck_enf_clue_item_status CHECK (item_status IN ('MATCHED','NOT_MATCHED','NOT_APPLICABLE','FAILED','WITHDRAWN')),
    CONSTRAINT ck_enf_clue_item_retry CHECK (retryable IN (0,1))
);
CREATE INDEX ix_enf_clue_discovery_item_task ON enf_clue_discovery_item(task_id, sequence_no);

CREATE TABLE enf_discovered_lead (
    lead_id VARCHAR(64) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    source_record_id VARCHAR(256) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    snapshot_id VARCHAR(64) NOT NULL,
    snapshot_sha256 VARCHAR(64) NOT NULL,
    source_availability VARCHAR(32) NOT NULL,
    source_excerpt VARCHAR(1000),
    suspected_types_json CLOB NOT NULL,
    processing_status VARCHAR(32) NOT NULL,
    review_required SMALLINT NOT NULL,
    usage_scope VARCHAR(32) NOT NULL,
    conclusion_scope VARCHAR(64) NOT NULL,
    first_found_at TIMESTAMP NOT NULL,
    version_no INTEGER NOT NULL,
    created_by VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(256) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_discovered_lead PRIMARY KEY (lead_id),
    CONSTRAINT uq_enf_discovered_lead_source UNIQUE (organization_ref, source_id, source_record_id, source_version),
    CONSTRAINT ck_enf_lead_availability CHECK (source_availability IN ('AVAILABLE','WITHDRAWN')),
    CONSTRAINT ck_enf_lead_status CHECK (processing_status IN ('PENDING_VERIFICATION','VERIFYING','TRANSFERRED','EXCLUDED','CLOSED')),
    CONSTRAINT ck_enf_lead_review CHECK (review_required IN (0,1)),
    CONSTRAINT ck_enf_lead_usage CHECK (usage_scope='DEVELOPMENT_ONLY'),
    CONSTRAINT ck_enf_lead_conclusion CHECK (conclusion_scope='NOT_AN_INFRINGEMENT_FINDING')
);
CREATE INDEX ix_enf_discovered_lead_org ON enf_discovered_lead(organization_ref, updated_at);

CREATE TABLE enf_clue_discovery_task_lead (
    task_id VARCHAR(64) NOT NULL,
    lead_id VARCHAR(64) NOT NULL,
    linked_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_clue_discovery_task_lead PRIMARY KEY (task_id, lead_id)
);

CREATE TABLE enf_discovered_lead_match (
    match_id VARCHAR(64) NOT NULL,
    lead_id VARCHAR(64) NOT NULL,
    rule_id VARCHAR(128) NOT NULL,
    rule_version INTEGER NOT NULL,
    rule_revision INTEGER NOT NULL,
    rule_sha256 VARCHAR(64) NOT NULL,
    suspected_type VARCHAR(64) NOT NULL,
    matched_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_discovered_lead_match PRIMARY KEY (match_id),
    CONSTRAINT uq_enf_discovered_lead_match UNIQUE (lead_id, rule_id, rule_version, rule_revision)
);
CREATE INDEX ix_enf_discovered_lead_match_lead ON enf_discovered_lead_match(lead_id, matched_at);

CREATE TABLE enf_discovered_lead_condition (
    result_id VARCHAR(64) NOT NULL,
    match_id VARCHAR(64) NOT NULL,
    sequence_no INTEGER NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    operator_name VARCHAR(32) NOT NULL,
    field_path VARCHAR(512),
    matched SMALLINT NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_discovered_lead_condition PRIMARY KEY (result_id),
    CONSTRAINT ck_enf_lead_condition_match CHECK (matched IN (0,1))
);
CREATE INDEX ix_enf_discovered_lead_condition_match ON enf_discovered_lead_condition(match_id, sequence_no);

CREATE TABLE enf_discovered_lead_status_history (
    history_id VARCHAR(64) NOT NULL,
    lead_id VARCHAR(64) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    lead_version INTEGER NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    reason_text VARCHAR(1000),
    actor_ref VARCHAR(256) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_discovered_lead_history PRIMARY KEY (history_id)
);
CREATE INDEX ix_enf_discovered_lead_history_lead ON enf_discovered_lead_status_history(lead_id, lead_version);

CREATE TABLE enf_discovered_lead_duplicate (
    link_id VARCHAR(64) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    left_lead_id VARCHAR(64) NOT NULL,
    right_lead_id VARCHAR(64) NOT NULL,
    link_status VARCHAR(32) NOT NULL,
    version_no INTEGER NOT NULL,
    reason_text VARCHAR(1000) NOT NULL,
    marked_by VARCHAR(256) NOT NULL,
    marked_at TIMESTAMP NOT NULL,
    reviewed_by VARCHAR(256),
    reviewed_at TIMESTAMP,
    CONSTRAINT pk_enf_discovered_lead_duplicate PRIMARY KEY (link_id),
    CONSTRAINT uq_enf_discovered_lead_duplicate UNIQUE (organization_ref, left_lead_id, right_lead_id),
    CONSTRAINT ck_enf_lead_duplicate_status CHECK (link_status IN ('SUSPECTED','CONFIRMED','REJECTED'))
);

CREATE TABLE enf_clue_discovery_audit (
    audit_id VARCHAR(64) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    object_type VARCHAR(32) NOT NULL,
    object_id VARCHAR(128) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(256) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    detail_json CLOB NOT NULL,
    CONSTRAINT pk_enf_clue_discovery_audit PRIMARY KEY (audit_id)
);
CREATE INDEX ix_enf_clue_discovery_audit_object ON enf_clue_discovery_audit(object_type, object_id, occurred_at);
