ALTER TABLE con4_search_attempt ADD rerank_model_version VARCHAR(300);
ALTER TABLE con4_search_attempt ADD rerank_status VARCHAR(32);
ALTER TABLE con4_search_attempt ADD CONSTRAINT ck_con4_search_rerank
    CHECK ((rerank_model_version IS NULL AND rerank_status IS NULL)
        OR (rerank_model_version IS NOT NULL
            AND rerank_status IN ('SUCCEEDED','RERANK_FAILED')));

ALTER TABLE con4_result_member ADD rerank_score DECIMAL(24, 12);
ALTER TABLE con4_result_member ADD rerank_rank INT;
ALTER TABLE con4_result_member ADD CONSTRAINT ck_con4_result_rerank
    CHECK ((rerank_score IS NULL AND rerank_rank IS NULL)
        OR (rerank_score IS NOT NULL AND rerank_rank > 0));
