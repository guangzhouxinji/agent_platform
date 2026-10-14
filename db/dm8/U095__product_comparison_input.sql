ALTER TABLE con5_comparison_input ADD CONSTRAINT ck_con5_comparison_empty CHECK(1=0);
DROP TABLE con5_comparison_event;
DROP TABLE con5_comparison_current;
DROP TABLE con5_comparison_candidate;
DROP TABLE con5_comparison_input;
