package cn.xinji.agentplatform.security.product;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dm.jdbc.driver.Configuration;
import dm.jdbc.driver.DmdbConnection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** Real driver byte-contract checks, not patent content, source admission, or HTTP acceptance. */
class ProductLobTransportTest {
    private record Row(int id, String text, String optional, String nativeTimestamp) { }

    @Test void optimizedFetchPreservesMultiBlockUnicodeNullEmptyAndNativeTimeWithoutResultCache() {
        String user = System.getenv("DM8_JDBC_USER");
        Assumptions.assumeTrue(user != null && user.matches("TST[A-F0-9]{8}"),
                "Requires the owned DM8 regression schema; never use a business schema");
        String table = "LOBC" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String original = "定位原文𠮷😀:é\n".repeat(12000);
        String updated = "修改后证据𠮷😀".repeat(9000);
        assertThat(original.length()).isGreaterThan(32000);
        try (var baseline = pool(false); var optimized = pool(true)) {
            for (var pool : List.of(baseline, optimized)) {
                try (var connection = pool.getConnection()) {
                    var nativeConnection = connection.unwrap(DmdbConnection.class);
                    assertThat(nativeConnection.lobFetchOptimize).isEqualTo(pool == optimized);
                    assertThat(nativeConnection.lobMode).isEqualTo(1);
                    assertThat(nativeConnection.lobReadWriteLen).isEqualTo(32000);
                    assertThat(nativeConnection.clobAsString).isFalse();
                    assertThat(nativeConnection.enRsCache).isFalse();
                    assertThat(nativeConnection.isBdtaRS).isFalse();
                } catch (java.sql.SQLException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            var db = new JdbcTemplate(baseline);
            var candidate = new JdbcTemplate(optimized);
            db.execute("CREATE TABLE " + table
                    + " (id INT PRIMARY KEY, original_text CLOB, optional_text CLOB, recorded_at TIMESTAMP(6))");
            try {
                var time = Timestamp.from(Instant.parse("2026-10-01T00:00:00.123456Z"));
                db.update("INSERT INTO " + table + " VALUES(?,?,?,?)", 1, original, null, time);
                db.update("INSERT INTO " + table + " VALUES(?,?,?,?)", 2, "", "", time);
                List<Row> expected = read(db, table);
                assertThat(expected).hasSize(2);
                assertThat(expected.getFirst().text()).isEqualTo(original);
                assertThat(expected.getFirst().optional()).isNull();
                assertThat(expected.getFirst().nativeTimestamp()).endsWith(".123456");
                for (int repeat = 0; repeat < 3; repeat++)
                    assertThat(read(candidate, table)).isEqualTo(expected);

                db.update("UPDATE " + table + " SET original_text=? WHERE id=1", updated);
                assertThat(read(candidate, table)).isEqualTo(read(db, table));
                assertThat(read(candidate, table).getFirst().text()).isEqualTo(updated);
                var transaction = new TransactionTemplate(new DataSourceTransactionManager(optimized));
                transaction.executeWithoutResult(status -> {
                    candidate.update("UPDATE " + table + " SET original_text=? WHERE id=1", original);
                    assertThat(read(candidate, table).getFirst().text()).isEqualTo(original);
                    status.setRollbackOnly();
                });
                assertThat(read(candidate, table)).isEqualTo(read(db, table));
                assertThat(read(candidate, table).getFirst().text()).isEqualTo(updated);
            } finally {
                db.execute("DROP TABLE " + table);
            }
        }
    }

    private static List<Row> read(JdbcTemplate db, String table) {
        return db.query("SELECT id,original_text,optional_text,recorded_at FROM " + table + " ORDER BY id",
                (rows, ordinal) -> new Row(rows.getInt(1), rows.getString(2),
                        rows.getString(3), rows.getString(4)));
    }

    private static HikariDataSource pool(boolean optimized) {
        var config = new HikariConfig();
        config.setJdbcUrl(System.getenv("DM8_JDBC_URL"));
        config.setUsername(System.getenv("DM8_JDBC_USER"));
        config.setPassword(System.getenv("DM8_JDBC_PASSWORD"));
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.addDataSourceProperty(Configuration.lobFetchOptimize.getName(), optimized);
        config.addDataSourceProperty(Configuration.enRsCache.getName(), false);
        config.addDataSourceProperty(Configuration.isBdtaRS.getName(), false);
        return new HikariDataSource(config);
    }
}
