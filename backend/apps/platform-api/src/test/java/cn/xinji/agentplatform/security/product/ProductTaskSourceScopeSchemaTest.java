package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ProductTaskSourceScopeSchemaTest {
    @Test void scopeRequiresPersistedTaskAndAdmissionAndReversesWithoutChangingPredecessors() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source-scope-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        for (String script : new String[] {"V032__product_identity.sql",
                "V034__product_consultation_initial_task.sql", "V036__product_source_candidates.sql",
                "V037__product_source_admission.sql", "V038__product_task_source_scope.sql"})
            script(source, "db/dm8/" + script);
        JdbcTemplate db = new JdbcTemplate(source);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_task_source_current", Integer.class)).isZero();
        assertThatThrownBy(() -> db.update("INSERT INTO con4_task_source_scope(scope_id,scope_version,"
                        + "task_id,organization_id,task_version,source_id,contract_version,decision_id,"
                        + "source_epoch,source_row_version,status,expires_at,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), 1,
                UUID.randomUUID().toString(), "organization-1", 1, "source-1", "contract-1",
                UUID.randomUUID().toString(), 1, 1, "ACTIVE",
                Timestamp.from(Instant.parse("2026-09-28T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-27T00:00:00Z"))))
                .isInstanceOf(RuntimeException.class);
        script(source, "db/dm8/U038__product_task_source_scope.sql");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME='CON4_TASK_SOURCE_SCOPE'", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME='CON3_TASK'", Integer.class)).isEqualTo(1);
    }

    private static void script(JdbcDataSource source, String path) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(path)));
        }
    }
}
