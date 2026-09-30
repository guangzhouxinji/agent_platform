ALTER TABLE con4_search_attempt ADD query_plan_version VARCHAR(80);
ALTER TABLE con4_search_attempt ADD expansions_json VARCHAR(4000);
ALTER TABLE con4_search_attempt ADD expansion_sha CHAR(64);
ALTER TABLE con4_search_attempt ADD CONSTRAINT ck_con4_search_expansion
    CHECK ((query_plan_version IS NULL AND expansions_json IS NULL AND expansion_sha IS NULL)
        OR (query_plan_version IS NOT NULL AND expansions_json IS NOT NULL
            AND expansion_sha IS NOT NULL AND LENGTH(expansion_sha) = 64));
