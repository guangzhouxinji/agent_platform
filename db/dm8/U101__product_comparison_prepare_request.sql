-- Any retained request prevents destructive rollback.
ALTER TABLE con5_preparation_request ADD CONSTRAINT ck_con5_prepare_req_empty CHECK(1=0);
DROP TABLE con5_preparation_request;
