ALTER TABLE con4_result_member ADD lexical_rank INT;
ALTER TABLE con4_result_member ADD semantic_score DECIMAL(24, 12);
ALTER TABLE con4_result_member ADD semantic_rank INT;
ALTER TABLE con4_result_member ADD fusion_score DECIMAL(24, 12);
ALTER TABLE con4_result_member ADD CONSTRAINT ck_con4_result_ranks
    CHECK ((lexical_rank IS NULL OR lexical_rank > 0)
        AND (semantic_rank IS NULL OR semantic_rank > 0));

