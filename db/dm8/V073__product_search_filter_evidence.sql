ALTER TABLE con4_search_attempt ADD filter_version VARCHAR(80);
ALTER TABLE con4_search_attempt ADD filters_json VARCHAR(4000);
ALTER TABLE con4_search_attempt ADD filter_sha CHAR(64);
ALTER TABLE con4_search_attempt ADD CONSTRAINT ck_con4_search_filter
    CHECK ((filter_version IS NULL AND filters_json IS NULL AND filter_sha IS NULL)
        OR (filter_version IS NOT NULL AND filters_json IS NOT NULL
            AND filter_sha IS NOT NULL AND LENGTH(filter_sha) = 64));
