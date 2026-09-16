CREATE TABLE enf_work_item (
    work_item_id VARCHAR(36) NOT NULL,
    clue_source_id VARCHAR(36) NOT NULL,
    organization_ref VARCHAR(200) NOT NULL,
    owner_ref VARCHAR(200) NOT NULL,
    scope_code VARCHAR(40) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    record_version INT NOT NULL,
    CONSTRAINT pk_enf_work_item PRIMARY KEY (work_item_id),
    CONSTRAINT ck_enf_work_scope CHECK (scope_code IN ('SYNTHETIC_V0'))
);

CREATE TABLE enf_source_locator (
    source_id VARCHAR(36) NOT NULL,
    source_kind VARCHAR(40) NOT NULL,
    source_system VARCHAR(120) NOT NULL,
    source_object_id VARCHAR(200) NOT NULL,
    version_ref VARCHAR(200) NOT NULL,
    location_text VARCHAR(1000),
    retrieved_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_source_locator PRIMARY KEY (source_id),
    CONSTRAINT uq_enf_source_version UNIQUE (source_system, source_object_id, version_ref, location_text)
);

ALTER TABLE enf_work_item ADD CONSTRAINT fk_enf_work_clue_source
    FOREIGN KEY (clue_source_id) REFERENCES enf_source_locator (source_id);

CREATE TABLE enf_clue_analysis (
    analysis_id VARCHAR(36) NOT NULL,
    work_item_id VARCHAR(36) NOT NULL,
    analysis_version INT NOT NULL,
    input_clue_source_id VARCHAR(36) NOT NULL,
    previous_analysis_id VARCHAR(36),
    revision_kind VARCHAR(30) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL,
    priority_outcome VARCHAR(30) NOT NULL,
    machine_output_ref VARCHAR(200),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_clue_analysis PRIMARY KEY (analysis_id),
    CONSTRAINT uq_enf_clue_analysis_version UNIQUE (work_item_id, analysis_version),
    CONSTRAINT fk_enf_clue_analysis_work FOREIGN KEY (work_item_id) REFERENCES enf_work_item (work_item_id),
    CONSTRAINT fk_enf_clue_analysis_source FOREIGN KEY (input_clue_source_id) REFERENCES enf_source_locator (source_id),
    CONSTRAINT fk_enf_clue_analysis_previous FOREIGN KEY (previous_analysis_id) REFERENCES enf_clue_analysis (analysis_id),
    CONSTRAINT ck_enf_clue_revision CHECK (revision_kind IN ('MACHINE', 'HUMAN_REVISION')),
    CONSTRAINT ck_enf_clue_priority CHECK (priority_outcome IN ('TO_BE_EVALUATED'))
);

CREATE TABLE enf_clue_field (
    field_id VARCHAR(36) NOT NULL,
    analysis_id VARCHAR(36) NOT NULL,
    field_kind VARCHAR(30) NOT NULL,
    item_order INT NOT NULL,
    role_as_stated VARCHAR(100),
    value_text CLOB NOT NULL,
    normalized_value VARCHAR(1000),
    time_kind VARCHAR(30),
    source_id VARCHAR(36) NOT NULL,
    CONSTRAINT pk_enf_clue_field PRIMARY KEY (field_id),
    CONSTRAINT uq_enf_clue_field_order UNIQUE (analysis_id, field_kind, item_order),
    CONSTRAINT fk_enf_clue_field_analysis FOREIGN KEY (analysis_id) REFERENCES enf_clue_analysis (analysis_id),
    CONSTRAINT fk_enf_clue_field_source FOREIGN KEY (source_id) REFERENCES enf_source_locator (source_id),
    CONSTRAINT ck_enf_clue_field_kind CHECK (field_kind IN ('SUBJECT', 'SUSPECTED_FACT', 'TIME', 'PLACE', 'EVIDENCE_MENTION'))
);

CREATE TABLE enf_clue_issue (
    issue_id VARCHAR(36) NOT NULL,
    analysis_id VARCHAR(36) NOT NULL,
    issue_order INT NOT NULL,
    issue_kind VARCHAR(20) NOT NULL,
    field_name VARCHAR(100) NOT NULL,
    description_text VARCHAR(1000) NOT NULL,
    source_refs CLOB,
    CONSTRAINT pk_enf_clue_issue PRIMARY KEY (issue_id),
    CONSTRAINT fk_enf_clue_issue_analysis FOREIGN KEY (analysis_id) REFERENCES enf_clue_analysis (analysis_id),
    CONSTRAINT ck_enf_clue_issue_kind CHECK (issue_kind IN ('MISSING', 'CONFLICT'))
);

CREATE TABLE enf_clue_conflict_candidate (
    conflict_candidate_id VARCHAR(36) NOT NULL,
    issue_id VARCHAR(36) NOT NULL,
    candidate_order INT NOT NULL,
    value_text CLOB NOT NULL,
    normalized_value VARCHAR(1000),
    time_kind VARCHAR(30),
    source_id VARCHAR(36) NOT NULL,
    CONSTRAINT pk_enf_clue_conflict_candidate PRIMARY KEY (conflict_candidate_id),
    CONSTRAINT uq_enf_clue_conflict_order UNIQUE (issue_id, candidate_order),
    CONSTRAINT fk_enf_clue_conflict_issue FOREIGN KEY (issue_id) REFERENCES enf_clue_issue (issue_id),
    CONSTRAINT fk_enf_clue_conflict_source FOREIGN KEY (source_id) REFERENCES enf_source_locator (source_id),
    CONSTRAINT ck_enf_clue_conflict_order CHECK (candidate_order > 0)
);

CREATE TABLE enf_evidence_ref (
    evidence_ref_id VARCHAR(36) NOT NULL,
    work_item_id VARCHAR(36) NOT NULL,
    item_order INT NOT NULL,
    evidence_version INT NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    related_source_id VARCHAR(36) NOT NULL,
    controlled_resource_ref VARCHAR(500),
    collector_ref VARCHAR(200),
    collection_missing_reason VARCHAR(500),
    occurrence_time VARCHAR(100),
    collection_time VARCHAR(100),
    upload_time VARCHAR(100),
    availability VARCHAR(30) NOT NULL,
    original_evidence_ref_id VARCHAR(36),
    derived_kind VARCHAR(40),
    recorded_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_evidence_ref PRIMARY KEY (evidence_ref_id),
    CONSTRAINT uq_enf_evidence_version UNIQUE (work_item_id, source_id, evidence_version),
    CONSTRAINT fk_enf_evidence_work FOREIGN KEY (work_item_id) REFERENCES enf_work_item (work_item_id),
    CONSTRAINT fk_enf_evidence_source FOREIGN KEY (source_id) REFERENCES enf_source_locator (source_id),
    CONSTRAINT fk_enf_evidence_related FOREIGN KEY (related_source_id) REFERENCES enf_source_locator (source_id),
    CONSTRAINT fk_enf_evidence_original FOREIGN KEY (original_evidence_ref_id) REFERENCES enf_evidence_ref (evidence_ref_id),
    CONSTRAINT ck_enf_evidence_availability CHECK (availability IN ('AVAILABLE', 'UNAUTHORIZED', 'SOURCE_UNAVAILABLE', 'UNSUPPORTED_CONTENT', 'UPSTREAM_FAILURE')),
    CONSTRAINT ck_enf_evidence_collection CHECK (collector_ref IS NOT NULL OR collection_missing_reason IS NOT NULL)
);

CREATE TABLE enf_relation_set (
    relation_set_id VARCHAR(36) NOT NULL,
    work_item_id VARCHAR(36) NOT NULL,
    relation_version INT NOT NULL,
    clue_analysis_id VARCHAR(36) NOT NULL,
    rule_source_id VARCHAR(36) NOT NULL,
    previous_relation_set_id VARCHAR(36),
    completeness VARCHAR(30) NOT NULL,
    machine_output_ref VARCHAR(200),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_relation_set PRIMARY KEY (relation_set_id),
    CONSTRAINT uq_enf_relation_version UNIQUE (work_item_id, relation_version),
    CONSTRAINT fk_enf_relation_work FOREIGN KEY (work_item_id) REFERENCES enf_work_item (work_item_id),
    CONSTRAINT fk_enf_relation_clue FOREIGN KEY (clue_analysis_id) REFERENCES enf_clue_analysis (analysis_id),
    CONSTRAINT fk_enf_relation_rule FOREIGN KEY (rule_source_id) REFERENCES enf_source_locator (source_id),
    CONSTRAINT fk_enf_relation_previous FOREIGN KEY (previous_relation_set_id) REFERENCES enf_relation_set (relation_set_id),
    CONSTRAINT ck_enf_relation_completeness CHECK (completeness IN ('HAS_GAPS', 'HAS_CONFLICTS', 'PENDING_HUMAN_REVIEW'))
);

CREATE TABLE enf_relation_input (
    relation_set_id VARCHAR(36) NOT NULL,
    evidence_ref_id VARCHAR(36) NOT NULL,
    item_order INT NOT NULL,
    CONSTRAINT pk_enf_relation_input PRIMARY KEY (relation_set_id, evidence_ref_id),
    CONSTRAINT fk_enf_relation_input_set FOREIGN KEY (relation_set_id) REFERENCES enf_relation_set (relation_set_id),
    CONSTRAINT fk_enf_relation_input_evidence FOREIGN KEY (evidence_ref_id) REFERENCES enf_evidence_ref (evidence_ref_id)
);

CREATE TABLE enf_evidence_relation (
    relation_id VARCHAR(36) NOT NULL,
    relation_set_id VARCHAR(36) NOT NULL,
    item_order INT NOT NULL,
    issue_code VARCHAR(200) NOT NULL,
    evidence_ref_id VARCHAR(36) NOT NULL,
    relation_type VARCHAR(20) NOT NULL,
    reason_text CLOB NOT NULL,
    source_id VARCHAR(36) NOT NULL,
    pending_human_review CHAR(1) NOT NULL,
    CONSTRAINT pk_enf_evidence_relation PRIMARY KEY (relation_id),
    CONSTRAINT fk_enf_relation_item_set FOREIGN KEY (relation_set_id) REFERENCES enf_relation_set (relation_set_id),
    CONSTRAINT fk_enf_relation_item_evidence FOREIGN KEY (evidence_ref_id) REFERENCES enf_evidence_ref (evidence_ref_id),
    CONSTRAINT fk_enf_relation_item_source FOREIGN KEY (source_id) REFERENCES enf_source_locator (source_id),
    CONSTRAINT ck_enf_relation_type CHECK (relation_type IN ('SUPPORT', 'OPPOSE', 'CONFLICT', 'MISSING')),
    CONSTRAINT ck_enf_relation_pending CHECK (pending_human_review IN ('Y', 'N'))
);

CREATE TABLE enf_timeline_entry (
    timeline_entry_id VARCHAR(36) NOT NULL,
    relation_set_id VARCHAR(36) NOT NULL,
    item_order INT NOT NULL,
    time_kind VARCHAR(20) NOT NULL,
    original_value VARCHAR(500) NOT NULL,
    normalized_value VARCHAR(100),
    source_id VARCHAR(36) NOT NULL,
    CONSTRAINT pk_enf_timeline_entry PRIMARY KEY (timeline_entry_id),
    CONSTRAINT fk_enf_timeline_set FOREIGN KEY (relation_set_id) REFERENCES enf_relation_set (relation_set_id),
    CONSTRAINT fk_enf_timeline_source FOREIGN KEY (source_id) REFERENCES enf_source_locator (source_id),
    CONSTRAINT ck_enf_timeline_kind CHECK (time_kind IN ('OCCURRENCE', 'COLLECTION', 'UPLOAD'))
);

CREATE TABLE enf_relation_issue (
    relation_issue_id VARCHAR(36) NOT NULL,
    relation_set_id VARCHAR(36) NOT NULL,
    item_order INT NOT NULL,
    issue_kind VARCHAR(30) NOT NULL,
    affected_ref VARCHAR(200),
    description_text VARCHAR(1000) NOT NULL,
    source_id VARCHAR(36),
    CONSTRAINT pk_enf_relation_issue PRIMARY KEY (relation_issue_id),
    CONSTRAINT fk_enf_relation_issue_set FOREIGN KEY (relation_set_id) REFERENCES enf_relation_set (relation_set_id),
    CONSTRAINT fk_enf_relation_issue_source FOREIGN KEY (source_id) REFERENCES enf_source_locator (source_id),
    CONSTRAINT ck_enf_relation_issue_kind CHECK (issue_kind IN ('GAP', 'CONFLICT', 'UNPROCESSED'))
);

CREATE TABLE enf_aux_analysis (
    aux_analysis_id VARCHAR(36) NOT NULL,
    work_item_id VARCHAR(36) NOT NULL,
    analysis_version INT NOT NULL,
    clue_analysis_id VARCHAR(36) NOT NULL,
    relation_set_id VARCHAR(36) NOT NULL,
    rule_source_id VARCHAR(36) NOT NULL,
    right_status_source_id VARCHAR(36),
    previous_aux_analysis_id VARCHAR(36),
    outcome_type VARCHAR(30) NOT NULL,
    not_official_decision CHAR(1) NOT NULL,
    machine_output_ref VARCHAR(200),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_aux_analysis PRIMARY KEY (aux_analysis_id),
    CONSTRAINT uq_enf_aux_analysis_version UNIQUE (work_item_id, analysis_version),
    CONSTRAINT fk_enf_aux_work FOREIGN KEY (work_item_id) REFERENCES enf_work_item (work_item_id),
    CONSTRAINT fk_enf_aux_clue FOREIGN KEY (clue_analysis_id) REFERENCES enf_clue_analysis (analysis_id),
    CONSTRAINT fk_enf_aux_relation FOREIGN KEY (relation_set_id) REFERENCES enf_relation_set (relation_set_id),
    CONSTRAINT fk_enf_aux_rule FOREIGN KEY (rule_source_id) REFERENCES enf_source_locator (source_id),
    CONSTRAINT fk_enf_aux_right FOREIGN KEY (right_status_source_id) REFERENCES enf_source_locator (source_id),
    CONSTRAINT fk_enf_aux_previous FOREIGN KEY (previous_aux_analysis_id) REFERENCES enf_aux_analysis (aux_analysis_id),
    CONSTRAINT ck_enf_aux_outcome CHECK (outcome_type IN ('SUSPECTED_SUPPORT', 'EXCLUSION_BASIS', 'MATERIALS_INSUFFICIENT')),
    CONSTRAINT ck_enf_aux_official CHECK (not_official_decision IN ('Y'))
);

CREATE TABLE enf_analysis_input (
    aux_analysis_id VARCHAR(36) NOT NULL,
    evidence_ref_id VARCHAR(36) NOT NULL,
    item_order INT NOT NULL,
    CONSTRAINT pk_enf_analysis_input PRIMARY KEY (aux_analysis_id, evidence_ref_id),
    CONSTRAINT fk_enf_analysis_input_analysis FOREIGN KEY (aux_analysis_id) REFERENCES enf_aux_analysis (aux_analysis_id),
    CONSTRAINT fk_enf_analysis_input_evidence FOREIGN KEY (evidence_ref_id) REFERENCES enf_evidence_ref (evidence_ref_id)
);

CREATE TABLE enf_analysis_item (
    analysis_item_id VARCHAR(36) NOT NULL,
    aux_analysis_id VARCHAR(36) NOT NULL,
    item_order INT NOT NULL,
    content_kind VARCHAR(30) NOT NULL,
    content_text CLOB NOT NULL,
    source_refs CLOB NOT NULL,
    uncertainty_text VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_enf_analysis_item PRIMARY KEY (analysis_item_id),
    CONSTRAINT uq_enf_analysis_item_order UNIQUE (aux_analysis_id, item_order),
    CONSTRAINT fk_enf_analysis_item_analysis FOREIGN KEY (aux_analysis_id) REFERENCES enf_aux_analysis (aux_analysis_id),
    CONSTRAINT ck_enf_analysis_item_kind CHECK (content_kind IN ('CONFIRMED_FACT', 'PARTY_STATEMENT', 'MACHINE_INFERENCE'))
);

CREATE TABLE enf_analysis_issue (
    analysis_issue_id VARCHAR(36) NOT NULL,
    aux_analysis_id VARCHAR(36) NOT NULL,
    item_order INT NOT NULL,
    issue_kind VARCHAR(40) NOT NULL,
    description_text VARCHAR(1000) NOT NULL,
    affected_ref VARCHAR(200),
    CONSTRAINT pk_enf_analysis_issue PRIMARY KEY (analysis_issue_id),
    CONSTRAINT fk_enf_analysis_issue_analysis FOREIGN KEY (aux_analysis_id) REFERENCES enf_aux_analysis (aux_analysis_id),
    CONSTRAINT ck_enf_analysis_issue_kind CHECK (issue_kind IN ('MATERIALS_INSUFFICIENT', 'RULE_NOT_APPLICABLE', 'HISTORICAL_STATUS_MISSING', 'SOURCE_UNAVAILABLE', 'ANALYSIS_FAILURE', 'UNPROCESSED'))
);

CREATE TABLE enf_review_record (
    review_record_id VARCHAR(36) NOT NULL,
    work_item_id VARCHAR(36) NOT NULL,
    item_order INT NOT NULL,
    subject_type VARCHAR(30) NOT NULL,
    subject_id VARCHAR(36) NOT NULL,
    subject_version INT NOT NULL,
    action_type VARCHAR(20) NOT NULL,
    before_ref VARCHAR(100),
    after_ref VARCHAR(100),
    reason_text VARCHAR(1000),
    actor_ref VARCHAR(200) NOT NULL,
    acted_at TIMESTAMP NOT NULL,
    scope_code VARCHAR(40) NOT NULL,
    not_official_decision CHAR(1) NOT NULL,
    CONSTRAINT pk_enf_review_record PRIMARY KEY (review_record_id),
    CONSTRAINT fk_enf_review_work FOREIGN KEY (work_item_id) REFERENCES enf_work_item (work_item_id),
    CONSTRAINT ck_enf_review_subject CHECK (subject_type IN ('CLUE_ANALYSIS', 'RELATION_SET', 'AUXILIARY_ANALYSIS')),
    CONSTRAINT ck_enf_review_action CHECK (action_type IN ('REVISE', 'SUBMIT', 'CONFIRM', 'RETURN')),
    CONSTRAINT ck_enf_review_return_reason CHECK (action_type <> 'RETURN' OR reason_text IS NOT NULL),
    CONSTRAINT ck_enf_review_scope CHECK (scope_code IN ('SYNTHETIC_PROCESS_TEST')),
    CONSTRAINT ck_enf_review_official CHECK (not_official_decision IN ('Y'))
);

CREATE TABLE enf_review_impact (
    impact_id VARCHAR(36) NOT NULL,
    work_item_id VARCHAR(36) NOT NULL,
    item_order INT NOT NULL,
    change_kind VARCHAR(40) NOT NULL,
    changed_source_id VARCHAR(36),
    old_version_ref VARCHAR(200),
    new_version_ref VARCHAR(200),
    affected_type VARCHAR(30) NOT NULL,
    affected_id VARCHAR(36) NOT NULL,
    affected_version INT NOT NULL,
    reason_text VARCHAR(1000) NOT NULL,
    discovered_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_review_impact PRIMARY KEY (impact_id),
    CONSTRAINT fk_enf_impact_work FOREIGN KEY (work_item_id) REFERENCES enf_work_item (work_item_id),
    CONSTRAINT fk_enf_impact_source FOREIGN KEY (changed_source_id) REFERENCES enf_source_locator (source_id)
);

CREATE TABLE enf_idempotency_record (
    actor_ref VARCHAR(200) NOT NULL,
    operation_code VARCHAR(60) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    work_item_id VARCHAR(36) NOT NULL,
    result_type VARCHAR(40) NOT NULL,
    result_id VARCHAR(36) NOT NULL,
    result_version INT NOT NULL,
    snapshot_payload CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_enf_idempotency PRIMARY KEY (actor_ref, operation_code, idempotency_key),
    CONSTRAINT fk_enf_idempotency_work FOREIGN KEY (work_item_id) REFERENCES enf_work_item (work_item_id)
);

CREATE INDEX ix_enf_clue_analysis_work ON enf_clue_analysis (work_item_id, analysis_version);
CREATE INDEX ix_enf_clue_field_analysis ON enf_clue_field (analysis_id, field_kind);
CREATE INDEX ix_enf_evidence_work ON enf_evidence_ref (work_item_id, evidence_version);
CREATE INDEX ix_enf_relation_work ON enf_relation_set (work_item_id, relation_version);
CREATE INDEX ix_enf_relation_item_set ON enf_evidence_relation (relation_set_id, relation_type);
CREATE INDEX ix_enf_aux_work ON enf_aux_analysis (work_item_id, analysis_version);
CREATE INDEX ix_enf_review_work ON enf_review_record (work_item_id, acted_at);
CREATE INDEX ix_enf_impact_work ON enf_review_impact (work_item_id, discovered_at);
