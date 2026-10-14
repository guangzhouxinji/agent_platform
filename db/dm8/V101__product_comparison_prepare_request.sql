CREATE TABLE con5_preparation_request (
 task_id VARCHAR(36) NOT NULL, actor_issuer VARCHAR(500) NOT NULL,
 actor_subject VARCHAR(200) NOT NULL, request_id VARCHAR(180) NOT NULL,
 request_sha CHAR(64) NOT NULL, preparation_id VARCHAR(36) NOT NULL UNIQUE,
 PRIMARY KEY(task_id,actor_issuer,actor_subject,request_id),
 CONSTRAINT fk_con5_prepare_req FOREIGN KEY(task_id,preparation_id)
 REFERENCES con5_preparation(task_id,preparation_id)
);
