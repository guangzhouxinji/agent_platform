ALTER TABLE con5_matrix_invocation ADD CONSTRAINT ck_con5_matrix_empty CHECK(1=0);
DROP TABLE con5_matrix_receipt;
DROP TABLE con5_matrix_citation;
DROP TABLE con5_matrix_row;
DROP TABLE con5_matrix_report;
DROP TABLE con5_matrix_invocation;
