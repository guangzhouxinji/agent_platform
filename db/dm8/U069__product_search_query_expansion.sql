ALTER TABLE con4_search_attempt DROP CONSTRAINT ck_con4_search_expansion;
ALTER TABLE con4_search_attempt DROP COLUMN expansion_sha;
ALTER TABLE con4_search_attempt DROP COLUMN expansions_json;
ALTER TABLE con4_search_attempt DROP COLUMN query_plan_version;
