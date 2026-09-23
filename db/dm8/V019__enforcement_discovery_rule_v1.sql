CREATE TABLE enf_discovery_rule (
    rule_id VARCHAR(128) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    next_version INTEGER NOT NULL,
    created_by VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_discovery_rule PRIMARY KEY (rule_id),
    CONSTRAINT ck_enf_discovery_rule_next CHECK (next_version > 1)
);
CREATE INDEX ix_enf_discovery_rule_org ON enf_discovery_rule(organization_ref, created_at);

CREATE TABLE enf_discovery_rule_version (
    rule_id VARCHAR(128) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    rule_version INTEGER NOT NULL,
    revision_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    rule_name VARCHAR(256) NOT NULL,
    category VARCHAR(64) NOT NULL,
    rule_description VARCHAR(2000),
    engine_type VARCHAR(32) NOT NULL,
    usage_scope VARCHAR(32) NOT NULL,
    condition_json CLOB NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    created_by VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(256) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    published_by VARCHAR(256),
    published_at TIMESTAMP,
    CONSTRAINT pk_enf_discovery_rule_version PRIMARY KEY (rule_id, rule_version),
    CONSTRAINT ck_enf_rule_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED')),
    CONSTRAINT ck_enf_rule_version_category CHECK (category IN ('COUNTERFEIT_PATENT', 'PATENT_INFRINGEMENT', 'PUBLICITY_ISSUE')),
    CONSTRAINT ck_enf_rule_version_engine CHECK (engine_type = 'DETERMINISTIC_V1'),
    CONSTRAINT ck_enf_rule_version_usage CHECK (usage_scope = 'DEVELOPMENT_ONLY'),
    CONSTRAINT ck_enf_rule_version_numbers CHECK (rule_version > 0 AND revision_no > 0)
);
CREATE INDEX ix_enf_rule_version_org ON enf_discovery_rule_version(organization_ref, updated_at);

CREATE TABLE enf_discovery_rule_condition (
    rule_id VARCHAR(128) NOT NULL,
    rule_version INTEGER NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    parent_node_id VARCHAR(128),
    sequence_no INTEGER NOT NULL,
    depth_no INTEGER NOT NULL,
    operator_name VARCHAR(32) NOT NULL,
    field_path VARCHAR(512),
    comparison_value_json CLOB,
    CONSTRAINT pk_enf_discovery_rule_condition PRIMARY KEY (rule_id, rule_version, node_id),
    CONSTRAINT ck_enf_rule_condition_depth CHECK (depth_no BETWEEN 1 AND 8)
);
CREATE INDEX ix_enf_rule_condition_tree ON enf_discovery_rule_condition(rule_id, rule_version, parent_node_id, sequence_no);

CREATE TABLE enf_discovery_rule_source (
    rule_id VARCHAR(128) NOT NULL,
    rule_version INTEGER NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_discovery_rule_source PRIMARY KEY (rule_id, rule_version, source_id)
);

CREATE TABLE enf_discovery_rule_lifecycle (
    event_id VARCHAR(64) NOT NULL,
    rule_id VARCHAR(128) NOT NULL,
    rule_version INTEGER NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_ref VARCHAR(256) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_discovery_rule_lifecycle PRIMARY KEY (event_id)
);
CREATE INDEX ix_enf_rule_lifecycle_rule ON enf_discovery_rule_lifecycle(rule_id, rule_version, occurred_at);

CREATE TABLE enf_discovery_rule_dry_run (
    run_id VARCHAR(64) NOT NULL,
    rule_id VARCHAR(128) NOT NULL,
    rule_version INTEGER NOT NULL,
    rule_revision INTEGER NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    sample_id VARCHAR(128) NOT NULL,
    sample_version VARCHAR(128) NOT NULL,
    sample_origin VARCHAR(32) NOT NULL,
    input_snapshot_json CLOB NOT NULL,
    input_sha256 VARCHAR(64) NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    matched SMALLINT NOT NULL,
    unhandled_reason VARCHAR(128),
    executed_by VARCHAR(256) NOT NULL,
    executed_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_discovery_rule_dry_run PRIMARY KEY (run_id),
    CONSTRAINT ck_enf_rule_sample_origin CHECK (sample_origin = 'SYNTHETIC_LOCAL'),
    CONSTRAINT ck_enf_rule_run_status CHECK (result_status IN ('SUCCEEDED', 'TYPE_ERROR')),
    CONSTRAINT ck_enf_rule_run_matched CHECK (matched IN (0, 1))
);
CREATE INDEX ix_enf_rule_run_rule ON enf_discovery_rule_dry_run(rule_id, rule_version, executed_at);

CREATE TABLE enf_discovery_rule_condition_result (
    result_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    sequence_no INTEGER NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    operator_name VARCHAR(32) NOT NULL,
    field_path VARCHAR(512),
    matched SMALLINT NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    actual_value_json CLOB,
    CONSTRAINT pk_enf_discovery_rule_condition_result PRIMARY KEY (result_id),
    CONSTRAINT ck_enf_rule_condition_matched CHECK (matched IN (0, 1))
);
CREATE INDEX ix_enf_rule_condition_run ON enf_discovery_rule_condition_result(run_id, sequence_no);

CREATE TABLE enf_discovery_rule_audit (
    audit_id VARCHAR(64) NOT NULL,
    rule_id VARCHAR(128) NOT NULL,
    rule_version INTEGER NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    actor_ref VARCHAR(256) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    detail_json CLOB NOT NULL,
    CONSTRAINT pk_enf_discovery_rule_audit PRIMARY KEY (audit_id)
);
CREATE INDEX ix_enf_rule_audit_rule ON enf_discovery_rule_audit(rule_id, rule_version, occurred_at);
