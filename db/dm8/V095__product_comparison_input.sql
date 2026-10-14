CREATE TABLE con5_comparison_input (
    comparison_id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL,
    feature_revision_id VARCHAR(36) NOT NULL, feature_revision_number BIGINT NOT NULL,
    preparation_id VARCHAR(36) NOT NULL, task_version BIGINT NOT NULL,
    previous_comparison_id VARCHAR(36), input_json CLOB NOT NULL, input_sha VARCHAR(64) NOT NULL,
    candidate_count BIGINT NOT NULL, actor_issuer VARCHAR(300) NOT NULL, actor_subject VARCHAR(200) NOT NULL,
    request_id VARCHAR(180) NOT NULL, request_sha VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_con5_comparison_task UNIQUE(task_id,comparison_id),
    CONSTRAINT uq_con5_comparison_prepared UNIQUE(comparison_id,preparation_id),
    CONSTRAINT uq_con5_comparison_request UNIQUE(task_id,actor_issuer,actor_subject,request_id),
    CONSTRAINT fk_con5_comparison_feature FOREIGN KEY(task_id,feature_revision_id,feature_revision_number)
        REFERENCES con5_feature_revision(task_id,revision_id,revision_number),
    CONSTRAINT fk_con5_comparison_prepare FOREIGN KEY(task_id,preparation_id)
        REFERENCES con5_preparation(task_id,preparation_id),
    CONSTRAINT fk_con5_comparison_previous FOREIGN KEY(task_id,previous_comparison_id)
        REFERENCES con5_comparison_input(task_id,comparison_id),
    CONSTRAINT fk_con5_comparison_actor FOREIGN KEY(actor_issuer,actor_subject) REFERENCES id2_subject(issuer,subject_id),
    CONSTRAINT ck_con5_comparison_values CHECK(feature_revision_number>0 AND task_version>0
        AND candidate_count>0 AND candidate_count<=20 AND expires_at>created_at)
);
CREATE TABLE con5_comparison_candidate (
    comparison_id VARCHAR(36) NOT NULL, preparation_id VARCHAR(36) NOT NULL,
    document_role VARCHAR(16) NOT NULL, candidate_id VARCHAR(64) NOT NULL,
    ordinal BIGINT NOT NULL, status VARCHAR(20) NOT NULL,
    PRIMARY KEY(comparison_id,candidate_id),
    CONSTRAINT uq_con5_comparison_order UNIQUE(comparison_id,ordinal),
    CONSTRAINT fk_con5_comparison_slot FOREIGN KEY(comparison_id,preparation_id)
        REFERENCES con5_comparison_input(comparison_id,preparation_id),
    CONSTRAINT fk_con5_comparison_document FOREIGN KEY(preparation_id,document_role,candidate_id)
        REFERENCES con5_prepared_document(preparation_id,document_role,document_key),
    CONSTRAINT ck_con5_comparison_slot CHECK(document_role='CANDIDATE' AND ordinal>=0
        AND status IN ('QUEUED','RUNNING','COMMITTED','FAILED','CANCELLED'))
);
CREATE TABLE con5_comparison_current (
    task_id VARCHAR(36) PRIMARY KEY, comparison_id VARCHAR(36) NOT NULL,
    CONSTRAINT fk_con5_comparison_current FOREIGN KEY(task_id,comparison_id)
        REFERENCES con5_comparison_input(task_id,comparison_id)
);
CREATE TABLE con5_comparison_event (
    event_id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL,
    comparison_id VARCHAR(36) NOT NULL UNIQUE, actor_issuer VARCHAR(300) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL, request_sha VARCHAR(64) NOT NULL,
    input_sha VARCHAR(64) NOT NULL, occurred_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_con5_comparison_event FOREIGN KEY(task_id,comparison_id)
        REFERENCES con5_comparison_input(task_id,comparison_id),
    CONSTRAINT fk_con5_comparison_event_actor FOREIGN KEY(actor_issuer,actor_subject) REFERENCES id2_subject(issuer,subject_id)
);
