CREATE TABLE conp_workspace_register_receipt (
    receipt_id VARCHAR(36) PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    subject_id VARCHAR(200) NOT NULL,
    operator_organization VARCHAR(36) NOT NULL,
    maintainer_version BIGINT NOT NULL,
    certificate_sha CHAR(64) NOT NULL,
    key_sha CHAR(64) NOT NULL,
    request_sha CHAR(64) NOT NULL,
    workspace_sha CHAR(64) NOT NULL,
    result_sha CHAR(64) NOT NULL,
    prepared_at TIMESTAMP NOT NULL,
    receipt_until TIMESTAMP NOT NULL,
    CONSTRAINT uq_conp_wr_workspace UNIQUE(workspace_id),
    CONSTRAINT uq_conp_wr_request UNIQUE(request_id),
    CONSTRAINT uq_conp_wr_key UNIQUE(workspace_id,subject_id,certificate_sha,operation,key_sha),
    CONSTRAINT fk_conp_wr_workspace FOREIGN KEY(workspace_id) REFERENCES conp_workspace(workspace_id),
    CONSTRAINT ck_conp_wr CHECK(operation='REGISTER' AND maintainer_version>0
        AND request_id<>receipt_id AND prepared_at<receipt_until)
);
