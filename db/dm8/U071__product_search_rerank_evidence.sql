ALTER TABLE con4_result_member DROP CONSTRAINT ck_con4_result_rerank;
ALTER TABLE con4_result_member DROP COLUMN rerank_rank;
ALTER TABLE con4_result_member DROP COLUMN rerank_score;

ALTER TABLE con4_search_attempt DROP CONSTRAINT ck_con4_search_rerank;
ALTER TABLE con4_search_attempt DROP COLUMN rerank_status;
ALTER TABLE con4_search_attempt DROP COLUMN rerank_model_version;
