package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Test-only real JDBC setup, assertion and read-audit helpers; no product recovery or fabricated results. */
final class ProductGrantRecoveryEvidenceTestSupport {
    static final String ISSUER = "https://grant-recovery-component.example";
    static final String INTENT = "owned-unresolved-intent";
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private ProductGrantRecoveryEvidenceTestSupport() { }
    static ProductGrantRecoveryEvidence.Evidence assertRefusal(ProductGrantRecoveryEvidence reader,
                                                               String code, boolean stable) {
        var result = reader.inspect();
        assertEvidence(result, code, stable);
        assertThatThrownBy(reader::requireReady)
                .isInstanceOfSatisfying(ProductGrantRecoveryEvidence.Refused.class, refused -> {
                    assertThat(refused.evidence()).isEqualTo(result);
                    assertThat(refused.getCause()).isNull();
                    assertThat(refused.getMessage()).isEqualTo("GRANT_RECOVERY_ADMISSION:" + code);
                });
        return result;
    }

    static void assertEvidence(ProductGrantRecoveryEvidence.Evidence result, String code, boolean stable) {
        assertThat(result.format()).isEqualTo("GRANT_RECOVERY_ADMISSION/v1");
        assertThat(result.code()).isEqualTo(code);
        assertThat(result.observationsStable()).isEqualTo(stable);
        assertThat(result.repairAuthorized()).isFalse();
        assertThat(result.mutationPerformed()).isFalse();
    }

    static final class Fixture {
        final JdbcDataSource primarySource = source();
        final JdbcDataSource authoritySource = source();
        final JdbcTemplate primary = new JdbcTemplate(primarySource);
        final JdbcTemplate authority = new JdbcTemplate(authoritySource);
        final ReadAudit primaryAudit = new ReadAudit(primarySource);
        final ReadAudit authorityAudit = new ReadAudit(authoritySource);

        Fixture() throws Exception {
            try (var connection = primarySource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("db/dm8/V032__product_identity.sql"));
            }
            try (var connection = authoritySource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("db/identity-authority/V001__grant_watermark.sql"));
            }
            primary.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,"
                    + "credential_version,created_at) VALUES(?,'member','owned-member','not-a-login','ACTIVE',1,?)",
                    ISSUER, Timestamp.from(NOW));
            insertAuthority(7L, "READY", null, history());
        }
        ProductGrantRecoveryEvidence reader() {
            return new ProductGrantRecoveryEvidence(new JdbcTemplate(primaryAudit), new JdbcTemplate(authorityAudit), ISSUER);
        }
        String history() { return ProductGrantHistoryBatch.digest(primary, ISSUER, false, false); }
        List<Map<String, Object>> rows() {
            return authority.queryForList("SELECT issuer,generation,state,intent_id,history_sha FROM id2_authority_watermark");
        }
        void pending() {
            authority.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id=? WHERE issuer=?", INTENT, ISSUER);
        }
        void disableSubject() {
            primary.update("INSERT INTO id2_subject_event(event_id,issuer,subject_id,operation,before_version,"
                    + "after_version,actor_ref,occurred_at) VALUES(? ,?,'member','DISABLE',1,2,'owned-engineer',?)",
                    UUID.randomUUID().toString(), ISSUER, Timestamp.from(NOW));
            primary.update("UPDATE id2_subject SET status='DISABLED',credential_version=2,disabled_at=? WHERE issuer=?",
                    Timestamp.from(NOW), ISSUER);
        }
        void relaxedAuthority() {
            authority.execute("ALTER TABLE id2_authority_watermark RENAME TO retained_authority_watermark");
            authority.execute("CREATE TABLE id2_authority_watermark(issuer VARCHAR(300),generation BIGINT,"
                    + "state VARCHAR(16),intent_id VARCHAR(100),history_sha VARCHAR(64))");
        }
        void insertAuthority(Long generation, String state, String intent, String sha) {
            authority.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha)"
                    + " VALUES(?,?,?,?,?)", ISSUER, generation, state, intent, sha);
        }
        void assertNoWriteCalls() {
            assertThat(primaryAudit.writeAttempts.get()).isZero();
            assertThat(authorityAudit.writeAttempts.get()).isZero();
        }
        void assertUnwritten(List<Map<String, Object>> before) {
            assertThat(rows()).isEqualTo(before);
            assertNoWriteCalls();
        }
        private static JdbcDataSource source() {
            var ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:grant-recovery-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            ds.setUser("sa");
            ds.setPassword("");
            return ds;
        }
    }

    /** Delegates every read to real JDBC. Rejects and counts attempted writes, including transactions.
     * Query hooks change only test-owned data through separate owner connections; no query result is fabricated.
     * DataSource-level instrumentation survives ProductWatermarkJdbc.buffered(). */
    static final class ReadAudit extends AbstractDataSource {
        private final DataSource delegate;
        final AtomicInteger writeAttempts = new AtomicInteger();
        final AtomicInteger authorityReads = new AtomicInteger();
        final AtomicInteger historyReads = new AtomicInteger();
        BiConsumer<String, Integer> beforeQuery = (sql, count) -> { };
        ReadAudit(DataSource delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() throws SQLException { return connection(delegate.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return connection(delegate.getConnection(user, password));
        }
        private Connection connection(Connection raw) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("commit") || name.equals("rollback") || name.equals("prepareCall")
                                || (name.equals("setAutoCommit") && Boolean.FALSE.equals(args[0]))) reject();
                        if (name.equals("prepareStatement")) {
                            String sql = (String) args[0];
                            checkSql(sql);
                            return statement((Statement) invoke(raw, method, args), sql);
                        }
                        if (name.equals("createStatement")) return statement((Statement) invoke(raw, method, args), null);
                        return invoke(raw, method, args);
                    });
        }
        private Object statement(Statement raw, String preparedSql) {
            Class<?> type = raw instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("executeUpdate") || name.equals("executeLargeUpdate") || name.equals("addBatch")
                        || name.equals("executeBatch") || name.equals("executeLargeBatch")) reject();
                if (name.equals("execute") || name.equals("executeQuery")) {
                    String sql = preparedSql != null ? preparedSql : (String) args[0];
                    checkSql(sql);
                    String upper = sql.toUpperCase(Locale.ROOT);
                    int count = upper.contains("ID2_AUTHORITY_WATERMARK") ? authorityReads.incrementAndGet()
                            : upper.contains("UNION ALL") ? historyReads.incrementAndGet() : 0;
                    beforeQuery.accept(upper, count);
                }
                return invoke(raw, method, args);
            });
        }
        private void checkSql(String sql) throws SQLException {
            if (!sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT ")) reject();
        }
        private void reject() throws SQLException {
            writeAttempts.incrementAndGet();
            throw new SQLException("Reader attempted a write or transaction mutation");
        }
        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
        }
    }
}
