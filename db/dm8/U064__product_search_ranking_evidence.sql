ALTER TABLE con4_result_member DROP COLUMN field_scores_json;
ALTER TABLE con4_result_member DROP COLUMN matched_terms_json;
ALTER TABLE con4_result_member DROP COLUMN lexical_score;

ALTER TABLE con4_result_set DROP CONSTRAINT ck_con4_result_candidate_count;
ALTER TABLE con4_result_set DROP COLUMN candidate_count;

