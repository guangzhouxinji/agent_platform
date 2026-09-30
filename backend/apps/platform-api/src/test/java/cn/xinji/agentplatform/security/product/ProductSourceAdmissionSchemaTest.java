package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ProductSourceAdmissionSchemaTest {
    @Test void admissionAndIndependentWatermarkMigrationsAreReversibleAndConstrained() throws Exception {
        JdbcDataSource primary = database();
        JdbcDataSource authority = database();
        script(primary, "db/dm8/V036__product_source_candidates.sql");
        script(primary, "db/dm8/V037__product_source_admission.sql");
        script(authority, "db/identity-authority/V003__consultation_source_watermark.sql");

        JdbcTemplate db = new JdbcTemplate(primary);
        JdbcTemplate independent = new JdbcTemplate(authority);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_current", Integer.class)).isZero();
        assertThatThrownBy(() -> independent.update("INSERT INTO id4_source_watermark"
                + "(source_id,source_epoch,state,intent_id,history_sha) VALUES(?,?,'PENDING',NULL,?)",
                "source-1", 0, "a".repeat(64))).isInstanceOf(RuntimeException.class);
        independent.update("INSERT INTO id4_source_watermark"
                + "(source_id,source_epoch,state,intent_id,history_sha) VALUES(?,?,'PENDING',?,?)",
                "source-1", 0, UUID.randomUUID().toString(), "a".repeat(64));
        assertThatThrownBy(() -> independent.update("UPDATE id4_source_watermark "
                + "SET state='READY' WHERE source_id=?", "source-1"))
                .isInstanceOf(RuntimeException.class);

        script(primary, "db/dm8/U037__product_source_admission.sql");
        script(primary, "db/dm8/U036__product_source_candidates.sql");
        script(authority, "db/identity-authority/U003__consultation_source_watermark.sql");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME='CON4_SOURCE_CURRENT'", Integer.class)).isZero();
        assertThat(independent.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME='ID4_SOURCE_WATERMARK'", Integer.class)).isZero();
    }

    private static JdbcDataSource database() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source-schema-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    private static void script(JdbcDataSource source, String path) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(path)));
        }
    }
}
