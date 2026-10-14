-- One immutable approval reference cannot acquire a second budget by changing case/config.
-- Nullable only for controlled reconciliation of already persisted model allowances; runtime denies null.
ALTER TABLE adj3_model_budget ADD approval_key VARCHAR(64);
ALTER TABLE adj3_model_budget ADD CONSTRAINT uq_adj3_model_approval UNIQUE(approval_key);
