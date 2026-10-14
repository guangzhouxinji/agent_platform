CREATE TABLE con6_human_package (
    package_id VARCHAR(36) NOT NULL,
    package_revision BIGINT NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    organization_id VARCHAR(200) NOT NULL,
    data_scope VARCHAR(300) NOT NULL,
    task_version BIGINT NOT NULL,
    requester_issuer VARCHAR(300) NOT NULL,
    requester_subject VARCHAR(200) NOT NULL,
    manifest_json CLOB NOT NULL,
    manifest_sha VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    body_until TIMESTAMP NOT NULL,
    PRIMARY KEY (package_id, package_revision),
    CONSTRAINT uq_con6_package_task UNIQUE (task_id, package_id, package_revision),
    CONSTRAINT fk_con6_package_task FOREIGN KEY (task_id, organization_id)
        REFERENCES con3_task(task_id, organization_id),
    CONSTRAINT fk_con6_package_requester FOREIGN KEY (requester_issuer, requester_subject)
        REFERENCES id2_subject(issuer, subject_id),
    CONSTRAINT ck_con6_package_values CHECK (package_revision > 0 AND task_version > 0 AND body_until > created_at)
);

CREATE TABLE con6_human_handling (
    package_id VARCHAR(36) NOT NULL,
    package_revision BIGINT NOT NULL,
    processing_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    last_change_at TIMESTAMP NOT NULL,
    PRIMARY KEY (package_id, package_revision),
    CONSTRAINT fk_con6_handling_package FOREIGN KEY (package_id, package_revision)
        REFERENCES con6_human_package(package_id, package_revision),
    CONSTRAINT ck_con6_handling_values CHECK (processing_version > 0 AND state IN ('WAITING','CLAIMED','WITHDRAWN','RETURNED'))
);

CREATE TABLE con6_human_event (
    package_id VARCHAR(36) NOT NULL,
    package_revision BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    operation VARCHAR(24) NOT NULL,
    before_version BIGINT NOT NULL,
    after_version BIGINT NOT NULL,
    actor_issuer VARCHAR(300) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    manifest_sha VARCHAR(64) NOT NULL,
    request_sha VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    audit_until TIMESTAMP NOT NULL,
    PRIMARY KEY (package_id, package_revision, sequence_no),
    CONSTRAINT fk_con6_event_package FOREIGN KEY (package_id, package_revision)
        REFERENCES con6_human_package(package_id, package_revision),
    CONSTRAINT fk_con6_event_actor FOREIGN KEY (actor_issuer, actor_subject)
        REFERENCES id2_subject(issuer, subject_id),
    CONSTRAINT ck_con6_event_request CHECK (operation='REQUESTED' AND sequence_no=1
        AND before_version=0 AND after_version=1 AND audit_until>occurred_at)
);

CREATE TABLE con6_human_receipt (
    task_id VARCHAR(36) NOT NULL,
    actor_issuer VARCHAR(300) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    command_code VARCHAR(24) NOT NULL,
    request_id VARCHAR(180) NOT NULL,
    request_sha VARCHAR(64) NOT NULL,
    package_id VARCHAR(36) NOT NULL,
    package_revision BIGINT NOT NULL,
    event_sequence BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    audit_until TIMESTAMP NOT NULL,
    PRIMARY KEY (task_id, actor_issuer, actor_subject, command_code, request_id),
    CONSTRAINT uq_con6_receipt_result UNIQUE (package_id, package_revision, event_sequence),
    CONSTRAINT fk_con6_receipt_package FOREIGN KEY (task_id, package_id, package_revision)
        REFERENCES con6_human_package(task_id, package_id, package_revision),
    CONSTRAINT fk_con6_receipt_event FOREIGN KEY (package_id, package_revision, event_sequence)
        REFERENCES con6_human_event(package_id, package_revision, sequence_no),
    CONSTRAINT fk_con6_receipt_actor FOREIGN KEY (actor_issuer, actor_subject)
        REFERENCES id2_subject(issuer, subject_id),
    CONSTRAINT ck_con6_receipt_values CHECK (command_code='REQUEST' AND event_sequence=1 AND audit_until>created_at)
);
