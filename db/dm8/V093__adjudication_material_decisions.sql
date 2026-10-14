-- ADJ-only additive migration. V086/V090/V091/V092 and model allowance remain unchanged.
CREATE TABLE adj3_material_decision_head (
 case_id VARCHAR(120) PRIMARY KEY, organization_version BIGINT NOT NULL,
 CONSTRAINT fk_adj3_dec_head_case FOREIGN KEY(case_id) REFERENCES adj2_case_work(case_id),
 CONSTRAINT ck_adj3_dec_head CHECK(organization_version>=0)
);
CREATE TABLE adj3_material_decision_version (
 version_id VARCHAR(36) PRIMARY KEY, case_id VARCHAR(120) NOT NULL,
 run_id VARCHAR(36) NOT NULL, candidate_id VARCHAR(20) NOT NULL,
 organization_version BIGINT NOT NULL, base_version_id VARCHAR(36), first_marker VARCHAR(8),
 work_version BIGINT NOT NULL, input_version BIGINT NOT NULL,
 actor_identity VARCHAR(600) NOT NULL, actor_ref VARCHAR(200) NOT NULL,
 request_key VARCHAR(80) NOT NULL, request_sha VARCHAR(64) NOT NULL,
 policy_key VARCHAR(64) NOT NULL, source_sha VARCHAR(64) NOT NULL, input_sha VARCHAR(64) NOT NULL,
 action_code VARCHAR(8) NOT NULL, created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL,
 payload CLOB NOT NULL, payload_sha VARCHAR(64) NOT NULL,
 CONSTRAINT fk_adj3_dec_case FOREIGN KEY(case_id) REFERENCES adj2_case_work(case_id),
 CONSTRAINT fk_adj3_dec_base FOREIGN KEY(base_version_id) REFERENCES adj3_material_decision_version(version_id),
 CONSTRAINT uq_adj3_dec_org UNIQUE(case_id,organization_version),
 CONSTRAINT uq_adj3_dec_request UNIQUE(case_id,actor_identity,request_key),
 CONSTRAINT uq_adj3_dec_first UNIQUE(case_id,run_id,candidate_id,first_marker),
 CONSTRAINT uq_adj3_dec_base UNIQUE(base_version_id),
 CONSTRAINT ck_adj3_dec_versions CHECK(organization_version>=1 AND work_version>=2 AND input_version>=1 AND input_version<work_version),
 CONSTRAINT ck_adj3_dec_action CHECK(action_code IN('ACCEPT','EDIT','REJECT')),
 CONSTRAINT ck_adj3_dec_chain CHECK((base_version_id IS NULL AND first_marker IS NOT NULL AND first_marker='FIRST') OR (base_version_id IS NOT NULL AND first_marker IS NULL AND action_code='EDIT')),
 CONSTRAINT ck_adj3_dec_expiry CHECK(expires_at>created_at)
);
