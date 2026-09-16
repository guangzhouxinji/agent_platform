CREATE TABLE adj_source_locator (
    source_id VARCHAR(36) NOT NULL, source_kind VARCHAR(40) NOT NULL, source_system VARCHAR(120) NOT NULL,
    source_object_id VARCHAR(200) NOT NULL, version_ref VARCHAR(200) NOT NULL, location_text VARCHAR(1000) NOT NULL,
    retrieved_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_adj_source PRIMARY KEY (source_id),
    CONSTRAINT uq_adj_source_version UNIQUE (source_system, source_object_id, version_ref, location_text)
);

CREATE TABLE adj_work_item (
    work_item_id VARCHAR(36) NOT NULL, case_source_id VARCHAR(36) NOT NULL, organization_ref VARCHAR(200) NOT NULL,
    owner_ref VARCHAR(200) NOT NULL, scope_code VARCHAR(40) NOT NULL, created_at TIMESTAMP NOT NULL, row_version INT NOT NULL,
    CONSTRAINT pk_adj_work PRIMARY KEY (work_item_id),
    CONSTRAINT fk_adj_work_case FOREIGN KEY (case_source_id) REFERENCES adj_source_locator (source_id),
    CONSTRAINT ck_adj_work_scope CHECK (scope_code IN ('SYNTHETIC_V0')),
    CONSTRAINT ck_adj_work_version CHECK (row_version > 0)
);

CREATE TABLE adj_work_input (
    work_item_id VARCHAR(36) NOT NULL, input_order INT NOT NULL, material_source_id VARCHAR(36) NOT NULL,
    CONSTRAINT pk_adj_work_input PRIMARY KEY (work_item_id, input_order),
    CONSTRAINT uq_adj_work_input_source UNIQUE (work_item_id, material_source_id),
    CONSTRAINT fk_adj_input_work FOREIGN KEY (work_item_id) REFERENCES adj_work_item (work_item_id),
    CONSTRAINT fk_adj_input_source FOREIGN KEY (material_source_id) REFERENCES adj_source_locator (source_id),
    CONSTRAINT ck_adj_input_order CHECK (input_order > 0)
);

CREATE TABLE adj_material_inspection (
    inspection_id VARCHAR(36) NOT NULL, work_item_id VARCHAR(36) NOT NULL, inspection_version INT NOT NULL,
    previous_inspection_id VARCHAR(36), created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_adj_inspection PRIMARY KEY (inspection_id),
    CONSTRAINT uq_adj_inspection_version UNIQUE (work_item_id, inspection_version),
    CONSTRAINT fk_adj_inspection_work FOREIGN KEY (work_item_id) REFERENCES adj_work_item (work_item_id),
    CONSTRAINT fk_adj_inspection_previous FOREIGN KEY (previous_inspection_id) REFERENCES adj_material_inspection (inspection_id),
    CONSTRAINT ck_adj_inspection_version CHECK (inspection_version > 0)
);

CREATE TABLE adj_inspection_item (
    inspection_item_id VARCHAR(36) NOT NULL, inspection_id VARCHAR(36) NOT NULL, item_order INT NOT NULL,
    item_kind VARCHAR(30) NOT NULL, content_text CLOB NOT NULL, source_id VARCHAR(36),
    CONSTRAINT pk_adj_inspection_item PRIMARY KEY (inspection_item_id),
    CONSTRAINT uq_adj_inspection_item_order UNIQUE (inspection_id, item_kind, item_order),
    CONSTRAINT fk_adj_inspection_item_head FOREIGN KEY (inspection_id) REFERENCES adj_material_inspection (inspection_id),
    CONSTRAINT fk_adj_inspection_item_source FOREIGN KEY (source_id) REFERENCES adj_source_locator (source_id),
    CONSTRAINT ck_adj_inspection_kind CHECK (item_kind IN ('PARTY_STATEMENT', 'UNRESOLVED_FACT', 'MISSING', 'CONFLICT'))
);

CREATE TABLE adj_legal_candidate_set (
    candidate_set_id VARCHAR(36) NOT NULL, work_item_id VARCHAR(36) NOT NULL, candidate_set_version INT NOT NULL,
    inspection_id VARCHAR(36) NOT NULL, previous_candidate_set_id VARCHAR(36), limitations CLOB, created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_adj_candidate_set PRIMARY KEY (candidate_set_id),
    CONSTRAINT uq_adj_candidate_set_version UNIQUE (work_item_id, candidate_set_version),
    CONSTRAINT fk_adj_candidate_set_work FOREIGN KEY (work_item_id) REFERENCES adj_work_item (work_item_id),
    CONSTRAINT fk_adj_candidate_set_inspection FOREIGN KEY (inspection_id) REFERENCES adj_material_inspection (inspection_id),
    CONSTRAINT fk_adj_candidate_set_previous FOREIGN KEY (previous_candidate_set_id) REFERENCES adj_legal_candidate_set (candidate_set_id)
);

CREATE TABLE adj_legal_candidate (
    candidate_id VARCHAR(36) NOT NULL, candidate_set_id VARCHAR(36) NOT NULL, candidate_order INT NOT NULL,
    candidate_code VARCHAR(100) NOT NULL, legal_source_id VARCHAR(36) NOT NULL, location_text VARCHAR(1000) NOT NULL, doubts CLOB,
    CONSTRAINT pk_adj_candidate PRIMARY KEY (candidate_id),
    CONSTRAINT uq_adj_candidate_order UNIQUE (candidate_set_id, candidate_order),
    CONSTRAINT uq_adj_candidate_code UNIQUE (candidate_set_id, candidate_code),
    CONSTRAINT fk_adj_candidate_set FOREIGN KEY (candidate_set_id) REFERENCES adj_legal_candidate_set (candidate_set_id),
    CONSTRAINT fk_adj_candidate_source FOREIGN KEY (legal_source_id) REFERENCES adj_source_locator (source_id)
);

CREATE TABLE adj_citation (
    citation_id VARCHAR(36) NOT NULL, work_item_id VARCHAR(36) NOT NULL, citation_version INT NOT NULL,
    legal_source_id VARCHAR(36) NOT NULL, location_text VARCHAR(1000) NOT NULL, action_type VARCHAR(20) NOT NULL,
    reason_text VARCHAR(1000) NOT NULL, previous_citation_id VARCHAR(36), created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_adj_citation PRIMARY KEY (citation_id),
    CONSTRAINT uq_adj_citation_version UNIQUE (work_item_id, citation_version),
    CONSTRAINT fk_adj_citation_work FOREIGN KEY (work_item_id) REFERENCES adj_work_item (work_item_id),
    CONSTRAINT fk_adj_citation_source FOREIGN KEY (legal_source_id) REFERENCES adj_source_locator (source_id),
    CONSTRAINT fk_adj_citation_previous FOREIGN KEY (previous_citation_id) REFERENCES adj_citation (citation_id),
    CONSTRAINT ck_adj_citation_action CHECK (action_type IN ('SELECT', 'REVISE', 'REMOVE'))
);

CREATE TABLE adj_draft (
    draft_id VARCHAR(36) NOT NULL, work_item_id VARCHAR(36) NOT NULL, draft_version INT NOT NULL,
    inspection_id VARCHAR(36) NOT NULL, template_source_id VARCHAR(36) NOT NULL, previous_draft_id VARCHAR(36),
    missing_items CLOB, conflicts CLOB, is_draft CHAR(1) NOT NULL, created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_adj_draft PRIMARY KEY (draft_id),
    CONSTRAINT uq_adj_draft_version UNIQUE (work_item_id, draft_version),
    CONSTRAINT fk_adj_draft_work FOREIGN KEY (work_item_id) REFERENCES adj_work_item (work_item_id),
    CONSTRAINT fk_adj_draft_inspection FOREIGN KEY (inspection_id) REFERENCES adj_material_inspection (inspection_id),
    CONSTRAINT fk_adj_draft_template FOREIGN KEY (template_source_id) REFERENCES adj_source_locator (source_id),
    CONSTRAINT fk_adj_draft_previous FOREIGN KEY (previous_draft_id) REFERENCES adj_draft (draft_id),
    CONSTRAINT ck_adj_draft_only CHECK (is_draft IN ('Y'))
);

CREATE TABLE adj_draft_citation (
    draft_id VARCHAR(36) NOT NULL, citation_id VARCHAR(36) NOT NULL, citation_order INT NOT NULL,
    CONSTRAINT pk_adj_draft_citation PRIMARY KEY (draft_id, citation_id),
    CONSTRAINT uq_adj_draft_citation_order UNIQUE (draft_id, citation_order),
    CONSTRAINT fk_adj_draft_citation_draft FOREIGN KEY (draft_id) REFERENCES adj_draft (draft_id),
    CONSTRAINT fk_adj_draft_citation_value FOREIGN KEY (citation_id) REFERENCES adj_citation (citation_id)
);

CREATE TABLE adj_draft_section (
    section_id VARCHAR(36) NOT NULL, draft_id VARCHAR(36) NOT NULL, section_order INT NOT NULL,
    section_anchor VARCHAR(100) NOT NULL, content_text CLOB NOT NULL,
    CONSTRAINT pk_adj_section PRIMARY KEY (section_id),
    CONSTRAINT uq_adj_section_order UNIQUE (draft_id, section_order),
    CONSTRAINT uq_adj_section_anchor UNIQUE (draft_id, section_anchor),
    CONSTRAINT fk_adj_section_draft FOREIGN KEY (draft_id) REFERENCES adj_draft (draft_id)
);

CREATE TABLE adj_draft_section_source (
    section_id VARCHAR(36) NOT NULL, source_id VARCHAR(36) NOT NULL, source_order INT NOT NULL,
    CONSTRAINT pk_adj_section_source PRIMARY KEY (section_id, source_id),
    CONSTRAINT uq_adj_section_source_order UNIQUE (section_id, source_order),
    CONSTRAINT fk_adj_section_source_section FOREIGN KEY (section_id) REFERENCES adj_draft_section (section_id),
    CONSTRAINT fk_adj_section_source_locator FOREIGN KEY (source_id) REFERENCES adj_source_locator (source_id)
);

CREATE TABLE adj_test_review (
    review_id VARCHAR(36) NOT NULL, work_item_id VARCHAR(36) NOT NULL, draft_id VARCHAR(36) NOT NULL,
    draft_version INT NOT NULL, action_type VARCHAR(20) NOT NULL, reason_text VARCHAR(1000) NOT NULL,
    actor_ref VARCHAR(200) NOT NULL, acted_at TIMESTAMP NOT NULL, scope_code VARCHAR(40) NOT NULL,
    not_official_decision CHAR(1) NOT NULL,
    CONSTRAINT pk_adj_review PRIMARY KEY (review_id),
    CONSTRAINT fk_adj_review_work FOREIGN KEY (work_item_id) REFERENCES adj_work_item (work_item_id),
    CONSTRAINT fk_adj_review_draft FOREIGN KEY (draft_id) REFERENCES adj_draft (draft_id),
    CONSTRAINT ck_adj_review_action CHECK (action_type IN ('CONFIRM', 'RETURN')),
    CONSTRAINT ck_adj_review_scope CHECK (scope_code IN ('SYNTHETIC_PROCESS_TEST')),
    CONSTRAINT ck_adj_review_official CHECK (not_official_decision IN ('Y'))
);

CREATE TABLE adj_idempotency_record (
    actor_ref VARCHAR(200) NOT NULL, operation_code VARCHAR(60) NOT NULL, idempotency_key VARCHAR(200) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL, work_item_id VARCHAR(36) NOT NULL, result_type VARCHAR(40) NOT NULL,
    result_id VARCHAR(36) NOT NULL, result_version INT NOT NULL, snapshot_payload CLOB NOT NULL, created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_adj_idempotency PRIMARY KEY (actor_ref, operation_code, idempotency_key),
    CONSTRAINT fk_adj_idempotency_work FOREIGN KEY (work_item_id) REFERENCES adj_work_item (work_item_id)
);

CREATE INDEX ix_adj_inspection_work ON adj_material_inspection (work_item_id, inspection_version);
CREATE INDEX ix_adj_candidate_set_work ON adj_legal_candidate_set (work_item_id, candidate_set_version);
CREATE INDEX ix_adj_citation_work ON adj_citation (work_item_id, citation_version);
CREATE INDEX ix_adj_draft_work ON adj_draft (work_item_id, draft_version);
CREATE INDEX ix_adj_review_work ON adj_test_review (work_item_id, acted_at);
