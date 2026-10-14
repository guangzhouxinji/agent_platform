ALTER TABLE con5_candidate_attempt ADD CONSTRAINT ck_con5_attempt_empty_rollback CHECK(1=0);
DROP TABLE con5_candidate_lease;
DROP TABLE con5_candidate_attempt;
