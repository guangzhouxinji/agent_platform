ALTER TABLE con4_result_cursor ADD method_version VARCHAR(100) NOT NULL;
ALTER TABLE con4_result_cursor ADD page_size INT NOT NULL;
ALTER TABLE con4_result_cursor ADD CONSTRAINT ck_con4_cursor_page_size
    CHECK (page_size IN (10,20,50));

