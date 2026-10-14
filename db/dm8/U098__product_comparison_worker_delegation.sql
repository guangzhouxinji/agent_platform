-- Retained delegation intent is history, not disposable migration state.
ALTER TABLE con5_comparison_delegation ADD CONSTRAINT ck_con5_compare_delegate_empty CHECK(1=0);
DROP TABLE con5_comparison_delegation;
