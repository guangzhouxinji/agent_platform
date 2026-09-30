ALTER TABLE con4_result_set ADD candidate_count INT DEFAULT 0 NOT NULL;
ALTER TABLE con4_result_set ADD CONSTRAINT ck_con4_result_candidate_count
    CHECK (candidate_count >= 0);

ALTER TABLE con4_result_member ADD lexical_score DECIMAL(24, 12);
ALTER TABLE con4_result_member ADD matched_terms_json CLOB;
ALTER TABLE con4_result_member ADD field_scores_json CLOB;

