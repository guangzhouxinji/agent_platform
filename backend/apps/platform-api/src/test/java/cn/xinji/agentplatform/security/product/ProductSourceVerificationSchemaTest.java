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

class ProductSourceVerificationSchemaTest {
    @Test void verificationRequiresRegisteredExactContractAndCanReverseWhenEmpty() throws Exception {
        JdbcDataSource data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:source-verification-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        script(data, "V036__product_source_candidates.sql");
        script(data, "V039__product_source_verification.sql");
        var db = new JdbcTemplate(data);
        assertThatThrownBy(() -> db.update("INSERT INTO con4_source_verification(verification_id,"
                + "source_id,contract_version,source_version,candidate_row_version,manifest_ref,manifest_sha,"
                + "records_sha,inventory_sha,record_count,raw_count,verifier_ref,verified_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), "missing", "v1",
                "capture-1", 1, "manifest", "a".repeat(64), "b".repeat(64), "c".repeat(64),
                44, 47, "operator", Timestamp.from(Instant.now())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_verification", Integer.class)).isZero();
        script(data, "U039__product_source_verification.sql");
        assertThatThrownBy(() -> db.queryForObject("SELECT COUNT(*) FROM con4_source_verification",
                Integer.class)).isInstanceOf(org.springframework.jdbc.BadSqlGrammarException.class);
    }

    private static void script(JdbcDataSource source, String name) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8/" + name)));
        }
    }
}
