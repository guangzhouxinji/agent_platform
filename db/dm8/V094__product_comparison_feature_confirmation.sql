CREATE TABLE con5_preparation (
    preparation_id VARCHAR(36) PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL, organization_id VARCHAR(200) NOT NULL,
    task_version BIGINT NOT NULL, goal_id VARCHAR(36) NOT NULL, goal_revision BIGINT NOT NULL,
    input_id VARCHAR(36) NOT NULL, target_message_id VARCHAR(36),
    command_json CLOB NOT NULL, snapshot_json CLOB NOT NULL,
    prepare_sha VARCHAR(64) NOT NULL, material_sha VARCHAR(64) NOT NULL,
    actor_issuer VARCHAR(300) NOT NULL, actor_subject VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL,
    retention_ref VARCHAR(200) NOT NULL, retention_version BIGINT NOT NULL, retention_evidence_sha VARCHAR(64) NOT NULL,
    CONSTRAINT uq_con5_prepare_task UNIQUE(task_id,preparation_id),
    CONSTRAINT fk_con5_prepare_task FOREIGN KEY(task_id,organization_id) REFERENCES con3_task(task_id,organization_id),
    CONSTRAINT fk_con5_prepare_goal FOREIGN KEY(task_id,goal_id,goal_revision) REFERENCES con3_goal_revision(task_id,goal_id,revision),
    CONSTRAINT fk_con5_prepare_input FOREIGN KEY(input_id) REFERENCES con3_search_input(input_id),
    CONSTRAINT fk_con5_prepare_message FOREIGN KEY(task_id,target_message_id) REFERENCES con3_message(task_id,message_id),
    CONSTRAINT fk_con5_prepare_actor FOREIGN KEY(actor_issuer,actor_subject) REFERENCES id2_subject(issuer,subject_id),
    CONSTRAINT ck_con5_prepare_values CHECK(task_version>0 AND goal_revision>0 AND retention_version>0 AND expires_at>created_at)
);
CREATE TABLE con5_prepared_document (
    preparation_id VARCHAR(36) NOT NULL, document_role VARCHAR(16) NOT NULL, document_key VARCHAR(64) NOT NULL,
    ordinal BIGINT NOT NULL, source_id VARCHAR(200) NOT NULL, contract_version VARCHAR(100) NOT NULL,
    decision_id VARCHAR(36) NOT NULL, source_epoch BIGINT NOT NULL, source_row_version BIGINT NOT NULL,
    document_id VARCHAR(300) NOT NULL, document_version VARCHAR(200) NOT NULL, source_version VARCHAR(100) NOT NULL,
    raw_sha VARCHAR(64) NOT NULL, original_ref VARCHAR(500) NOT NULL, json_pointer VARCHAR(200) NOT NULL,
    branch_json CLOB NOT NULL, display_grant_id VARCHAR(36) NOT NULL, display_generation BIGINT NOT NULL,
    compare_grant_id VARCHAR(36) NOT NULL, compare_generation BIGINT NOT NULL, expires_at TIMESTAMP NOT NULL,
    PRIMARY KEY(preparation_id,document_role,document_key),
    CONSTRAINT uq_con5_doc_order UNIQUE(preparation_id,document_role,ordinal),
    CONSTRAINT fk_con5_doc_prepare FOREIGN KEY(preparation_id) REFERENCES con5_preparation(preparation_id),
    CONSTRAINT fk_con5_doc_admission FOREIGN KEY(decision_id,source_id,contract_version)
        REFERENCES con4_source_admission(decision_id,source_id,contract_version),
    CONSTRAINT fk_con5_doc_display FOREIGN KEY(display_grant_id) REFERENCES id2_grant(grant_id),
    CONSTRAINT fk_con5_doc_compare FOREIGN KEY(compare_grant_id) REFERENCES id2_grant(grant_id),
    CONSTRAINT ck_con5_doc_values CHECK(document_role IN ('TARGET','CANDIDATE') AND ordinal>=0
        AND source_epoch>0 AND source_row_version>0 AND display_generation>0 AND compare_generation>0)
);
CREATE TABLE con5_feature_revision (
    revision_id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, revision_number BIGINT NOT NULL,
    previous_revision_id VARCHAR(36), preparation_id VARCHAR(36) NOT NULL, prepare_sha VARCHAR(64) NOT NULL,
    target_sha VARCHAR(64) NOT NULL, package_sha VARCHAR(64) NOT NULL, feature_count BIGINT NOT NULL,
    actor_issuer VARCHAR(300) NOT NULL, actor_subject VARCHAR(200) NOT NULL,
    request_id VARCHAR(200) NOT NULL, request_sha VARCHAR(64) NOT NULL, statement_json CLOB NOT NULL,
    confirmed_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_con5_feature_task_id UNIQUE(task_id,revision_id),
    CONSTRAINT uq_con5_feature_exact UNIQUE(task_id,revision_id,revision_number),
    CONSTRAINT uq_con5_feature_number UNIQUE(task_id,revision_number),
    CONSTRAINT uq_con5_feature_request UNIQUE(task_id,actor_issuer,actor_subject,request_id),
    CONSTRAINT fk_con5_feature_prepare FOREIGN KEY(task_id,preparation_id) REFERENCES con5_preparation(task_id,preparation_id),
    CONSTRAINT fk_con5_feature_previous FOREIGN KEY(task_id,previous_revision_id) REFERENCES con5_feature_revision(task_id,revision_id),
    CONSTRAINT fk_con5_feature_actor FOREIGN KEY(actor_issuer,actor_subject) REFERENCES id2_subject(issuer,subject_id),
    CONSTRAINT ck_con5_feature_revision CHECK(revision_number>0 AND feature_count BETWEEN 1 AND 200 AND expires_at>confirmed_at)
);
CREATE TABLE con5_feature (
    revision_id VARCHAR(36) NOT NULL, feature_id VARCHAR(64) NOT NULL, ordinal BIGINT NOT NULL,
    parent_feature_id VARCHAR(64), char_start BIGINT NOT NULL, char_end BIGINT NOT NULL,
    original_version VARCHAR(200) NOT NULL, original_ref VARCHAR(500) NOT NULL, json_pointer VARCHAR(200) NOT NULL,
    original_sha VARCHAR(64) NOT NULL, byte_start BIGINT NOT NULL, byte_end BIGINT NOT NULL, span_sha VARCHAR(64) NOT NULL,
    claim_number BIGINT, user_text_json CLOB NOT NULL,
    PRIMARY KEY(revision_id,feature_id), CONSTRAINT uq_con5_feature_order UNIQUE(revision_id,ordinal),
    CONSTRAINT fk_con5_feature_revision FOREIGN KEY(revision_id) REFERENCES con5_feature_revision(revision_id),
    CONSTRAINT fk_con5_feature_parent FOREIGN KEY(revision_id,parent_feature_id) REFERENCES con5_feature(revision_id,feature_id),
    CONSTRAINT ck_con5_feature_span CHECK(ordinal>=0 AND char_start>=0 AND char_end>char_start
        AND byte_start>=0 AND byte_end>byte_start AND (claim_number IS NULL OR claim_number>0))
);
CREATE TABLE con5_feature_current (
    task_id VARCHAR(36) PRIMARY KEY, revision_id VARCHAR(36) NOT NULL, revision_number BIGINT NOT NULL,
    CONSTRAINT fk_con5_current_revision FOREIGN KEY(task_id,revision_id,revision_number)
        REFERENCES con5_feature_revision(task_id,revision_id,revision_number),
    CONSTRAINT ck_con5_current_number CHECK(revision_number>0)
);
CREATE TABLE con5_feature_event (
    event_id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, revision_id VARCHAR(36) NOT NULL UNIQUE,
    actor_issuer VARCHAR(300) NOT NULL, actor_subject VARCHAR(200) NOT NULL,
    request_sha VARCHAR(64) NOT NULL, package_sha VARCHAR(64) NOT NULL, occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con5_event_revision FOREIGN KEY(task_id,revision_id) REFERENCES con5_feature_revision(task_id,revision_id),
    CONSTRAINT fk_con5_event_actor FOREIGN KEY(actor_issuer,actor_subject) REFERENCES id2_subject(issuer,subject_id)
);
