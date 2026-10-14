-- Additive ADJ-only tables; never changes V086 case/history or current CON/ENF schemas.
CREATE TABLE adj3_model_budget (
 policy_key VARCHAR(64) PRIMARY KEY, case_id VARCHAR(120) NOT NULL, config_sha VARCHAR(64) NOT NULL,
 expires_at TIMESTAMP NOT NULL, calls BIGINT NOT NULL, input_tokens BIGINT NOT NULL,
 output_tokens BIGINT NOT NULL, cost_micros BIGINT NOT NULL, frozen INTEGER NOT NULL, last_seen TIMESTAMP NOT NULL,
 CONSTRAINT ck_adj3_budget CHECK(calls>=0 AND calls<=20 AND input_tokens>=0 AND input_tokens<=100000 AND output_tokens>=0 AND output_tokens<=20000 AND cost_micros>=0 AND cost_micros<=10000000 AND frozen IN(0,1))
);
CREATE TABLE adj3_material_run (
 run_id VARCHAR(36) PRIMARY KEY, policy_key VARCHAR(64) NOT NULL, request_key VARCHAR(100) NOT NULL,
 actor_ref VARCHAR(600) NOT NULL, request_sha VARCHAR(64) NOT NULL, input_tokens BIGINT NOT NULL,
 output_tokens BIGINT NOT NULL, cost_micros BIGINT NOT NULL, state VARCHAR(24) NOT NULL,
 created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL, payload CLOB, payload_sha VARCHAR(64),
 CONSTRAINT fk_adj3_run_policy FOREIGN KEY(policy_key) REFERENCES adj3_model_budget(policy_key),
 CONSTRAINT uq_adj3_run_request UNIQUE(policy_key,request_key),
 CONSTRAINT ck_adj3_run_state CHECK(state IN('RESERVED','COMMITTED','REJECTED','UNCERTAIN'))
);
