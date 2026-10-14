CREATE TABLE con5_matrix_dependency_material (
 attempt_id VARCHAR(36) PRIMARY KEY,
 material_json CLOB NOT NULL, material_sha CHAR(64) NOT NULL,
 CONSTRAINT fk_con5_dep_invocation FOREIGN KEY(attempt_id) REFERENCES con5_matrix_invocation(attempt_id)
);
CREATE TABLE con5_matrix_dependency_original (
 report_id VARCHAR(36) PRIMARY KEY, attempt_id VARCHAR(36) NOT NULL UNIQUE,
 original_json CLOB NOT NULL, original_sha CHAR(64) NOT NULL,
 CONSTRAINT fk_con5_dep_report FOREIGN KEY(report_id) REFERENCES con5_matrix_report(report_id),
 CONSTRAINT fk_con5_dep_material FOREIGN KEY(attempt_id) REFERENCES con5_matrix_dependency_material(attempt_id)
);
