-- Both immutable histories must be empty before the first destructive statement.
DECLARE
 history_count BIGINT;
BEGIN
 SELECT COUNT(*) INTO history_count FROM con5_matrix_dependency_material;
 IF history_count > 0 THEN
  RAISE_APPLICATION_ERROR(-20001, 'Nonempty reservation material prohibits reverse migration');
 END IF;
 SELECT COUNT(*) INTO history_count FROM con5_matrix_dependency_original;
 IF history_count > 0 THEN
  RAISE_APPLICATION_ERROR(-20001, 'Nonempty dependency original prohibits reverse migration');
 END IF;
END;
/
DROP TABLE con5_matrix_dependency_original;
DROP TABLE con5_matrix_dependency_material;
