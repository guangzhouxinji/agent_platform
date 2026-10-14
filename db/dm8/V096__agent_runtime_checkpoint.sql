-- Shared execution metadata only; domain facts, grants and official decisions remain domain-owned.
CREATE TABLE agent_run_head (
 run_id VARCHAR(120) PRIMARY KEY,
 agent_id VARCHAR(240) NOT NULL,
 task_id VARCHAR(240) NOT NULL,
 goal_id VARCHAR(240) NOT NULL,
 goal_version VARCHAR(240) NOT NULL,
 scope_ref VARCHAR(240) NOT NULL,
 head_version BIGINT NOT NULL CHECK (head_version >= 0),
 expires_at TIMESTAMP NOT NULL,
 created_at TIMESTAMP NOT NULL,
 updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_agent_run_parent ON agent_run_head(agent_id,task_id);
CREATE INDEX idx_agent_run_expiry ON agent_run_head(expires_at);
CREATE TABLE agent_run_revision (
 run_id VARCHAR(120) NOT NULL,
 revision BIGINT NOT NULL CHECK (revision >= 0),
 ciphertext CLOB NOT NULL,
 nonce VARCHAR(24) NOT NULL,
 payload_sha CHAR(64) NOT NULL,
 created_at TIMESTAMP NOT NULL,
 PRIMARY KEY (run_id,revision),
 FOREIGN KEY (run_id) REFERENCES agent_run_head(run_id)
);
