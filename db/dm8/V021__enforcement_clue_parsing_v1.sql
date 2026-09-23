CREATE TABLE enf_clue_parse_task (
    task_id VARCHAR(64) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    lead_id VARCHAR(64) NOT NULL,
    requester_ref VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    input_sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_version INTEGER NOT NULL,
    field_count INTEGER NOT NULL,
    issue_count INTEGER NOT NULL,
    error_code VARCHAR(128),
    parser_version VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT pk_enf_clue_parse_task PRIMARY KEY (task_id),
    CONSTRAINT uq_enf_clue_parse_idem UNIQUE (organization_ref, requester_ref, idempotency_key),
    CONSTRAINT ck_enf_clue_parse_task_status CHECK (status IN ('running','succeeded','partial_failed','failed'))
);
CREATE INDEX ix_enf_clue_parse_task_lead ON enf_clue_parse_task(organization_ref, lead_id, created_at);

CREATE TABLE enf_clue_parse_input (
    task_id VARCHAR(64) NOT NULL,
    lead_version INTEGER NOT NULL,
    snapshot_id VARCHAR(64) NOT NULL,
    snapshot_sha256 VARCHAR(64) NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    CONSTRAINT pk_enf_clue_parse_input PRIMARY KEY (task_id)
);

CREATE TABLE enf_clue_parse_result (
    result_id VARCHAR(64) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    lead_id VARCHAR(64) NOT NULL,
    result_version INTEGER NOT NULL,
    task_id VARCHAR(64),
    previous_result_id VARCHAR(64),
    revision_kind VARCHAR(32) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    model_id VARCHAR(128),
    model_version VARCHAR(128),
    model_usage VARCHAR(32) NOT NULL,
    priority_level VARCHAR(32) NOT NULL,
    priority_reason VARCHAR(128) NOT NULL,
    not_legal_probability SMALLINT NOT NULL,
    review_required SMALLINT NOT NULL,
    created_by VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(256) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_clue_parse_result PRIMARY KEY (result_id),
    CONSTRAINT uq_enf_clue_parse_version UNIQUE (organization_ref, lead_id, result_version),
    CONSTRAINT ck_enf_clue_parse_revision CHECK (revision_kind IN ('MACHINE','HUMAN_REVISION')),
    CONSTRAINT ck_enf_clue_parse_review CHECK (review_status IN ('PENDING_REVIEW','CONFIRMED','RETURNED')),
    CONSTRAINT ck_enf_clue_parse_model CHECK (model_usage='NO_MODEL_USED' AND model_id IS NULL AND model_version IS NULL),
    CONSTRAINT ck_enf_clue_parse_priority CHECK (priority_level='TO_BE_EVALUATED' AND priority_reason='FACTORS_NOT_CONFIRMED' AND not_legal_probability=1),
    CONSTRAINT ck_enf_clue_parse_review_required CHECK (review_required IN (0,1))
);
CREATE INDEX ix_enf_clue_parse_result_lead ON enf_clue_parse_result(organization_ref, lead_id, result_version);

CREATE TABLE enf_clue_parse_field (
    field_id VARCHAR(64) NOT NULL,
    result_id VARCHAR(64) NOT NULL,
    field_kind VARCHAR(32) NOT NULL,
    item_order INTEGER NOT NULL,
    role_as_stated VARCHAR(128),
    raw_value CLOB NOT NULL,
    normalized_value VARCHAR(1000),
    credit_code VARCHAR(64),
    source_fragment VARCHAR(2000) NOT NULL,
    field_path VARCHAR(512) NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    reference_status VARCHAR(32),
    source_ref VARCHAR(128),
    source_version VARCHAR(64),
    source_sha256 VARCHAR(64),
    CONSTRAINT pk_enf_clue_parse_field PRIMARY KEY (field_id),
    CONSTRAINT uq_enf_clue_parse_field_order UNIQUE (result_id, item_order),
    CONSTRAINT ck_enf_clue_parse_field_kind CHECK (field_kind IN ('SUBJECT','FACT','TIME','PLACE','EVIDENCE_REFERENCE','CLASSIFICATION')),
    CONSTRAINT ck_enf_clue_parse_source_kind CHECK (source_kind IN ('SOURCE_SNAPSHOT','RULE_MATCH','HUMAN_REVISION')),
    CONSTRAINT ck_enf_clue_parse_source_trace CHECK ((source_kind='HUMAN_REVISION' AND source_ref IS NULL AND source_sha256 IS NULL) OR (source_kind IN ('SOURCE_SNAPSHOT','RULE_MATCH') AND source_ref IS NOT NULL AND source_sha256 IS NOT NULL)),
    CONSTRAINT ck_enf_clue_parse_reference CHECK ((field_kind='EVIDENCE_REFERENCE' AND reference_status='PENDING_CONNECTION') OR (field_kind<>'EVIDENCE_REFERENCE' AND reference_status IS NULL))
);
CREATE INDEX ix_enf_clue_parse_field_result ON enf_clue_parse_field(result_id, field_kind, item_order);

CREATE TABLE enf_clue_parse_issue (
    issue_id VARCHAR(64) NOT NULL,
    result_id VARCHAR(64) NOT NULL,
    issue_order INTEGER NOT NULL,
    issue_kind VARCHAR(32) NOT NULL,
    field_path VARCHAR(512) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_clue_parse_issue PRIMARY KEY (issue_id),
    CONSTRAINT uq_enf_clue_parse_issue_order UNIQUE (result_id, issue_order),
    CONSTRAINT ck_enf_clue_parse_issue_kind CHECK (issue_kind IN ('MISSING','TYPE_ERROR','UNPROCESSED','NORMALIZATION_FAILED'))
);

CREATE TABLE enf_clue_parse_change (
    change_id VARCHAR(64) NOT NULL,
    result_id VARCHAR(64) NOT NULL,
    change_order INTEGER NOT NULL,
    before_json CLOB,
    after_json CLOB NOT NULL,
    reason_text VARCHAR(1000) NOT NULL,
    actor_ref VARCHAR(256) NOT NULL,
    changed_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_clue_parse_change PRIMARY KEY (change_id)
);

CREATE TABLE enf_clue_parse_review (
    review_id VARCHAR(64) NOT NULL,
    result_id VARCHAR(64) NOT NULL,
    result_version INTEGER NOT NULL,
    from_status VARCHAR(32) NOT NULL,
    to_status VARCHAR(32) NOT NULL,
    reason_text VARCHAR(1000),
    reviewer_ref VARCHAR(256) NOT NULL,
    reviewed_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    CONSTRAINT pk_enf_clue_parse_review PRIMARY KEY (review_id),
    CONSTRAINT ck_enf_clue_parse_review_to CHECK (to_status IN ('CONFIRMED','RETURNED'))
);

CREATE TABLE enf_clue_parse_audit (
    audit_id VARCHAR(64) NOT NULL,
    organization_ref VARCHAR(128) NOT NULL,
    object_type VARCHAR(32) NOT NULL,
    object_id VARCHAR(128) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    actor_ref VARCHAR(256) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    detail_json CLOB NOT NULL,
    CONSTRAINT pk_enf_clue_parse_audit PRIMARY KEY (audit_id)
);
CREATE INDEX ix_enf_clue_parse_audit_object ON enf_clue_parse_audit(object_type, object_id, occurred_at);
