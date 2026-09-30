package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ProductSourceCandidatesTest {
    @Test void onlyCandidateAndWithdrawalArePersistedWithIdempotentAudit() throws Exception {
        JdbcDataSource source = database();
        script(source, "db/dm8/V036__product_source_candidates.sql");
        JdbcTemplate db = new JdbcTemplate(source);
        var candidates = service(db, source);
        var input = declaration("source-1", "v1", "snapshot-1");

        assertThatThrownBy(() -> candidates.register(null, "first", "operator#cert"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> candidates.register(declaration("source-1", "v1", "snapshot-1",
                Map.of("title", List.of("MODEL"))), "bad", "operator#cert"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_contract", Integer.class)).isZero();
        var registered = candidates.register(input, "first", "operator#cert");
        assertThat(registered.status()).isEqualTo("CANDIDATE");
        assertThat(registered.retentionRef()).isEqualTo("source-retention-approval");
        assertThat(candidates.register(input, "first", "operator#cert")).isEqualTo(registered);
        assertThatThrownBy(() -> candidates.register(input, "another", "operator#cert"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> candidates.register(declaration("source-1", "v2", "snapshot-1"),
                "second", "operator#cert")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> candidates.register(declaration("source-2", "v1", "snapshot-2"),
                "first", "operator#cert")).isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_event", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_request", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_contract WHERE status='ACTIVE'",
                Integer.class)).isZero();

        assertThatThrownBy(() -> candidates.withdraw("source-1", "v1", 2, "wrong", "operator#cert"))
                .isInstanceOf(ResponseStatusException.class);
        var withdrawn = candidates.withdraw("source-1", "v1", 1, "withdraw", "operator#cert");
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.version()).isEqualTo(2);
        assertThat(candidates.withdraw("source-1", "v1", 1, "withdraw", "operator#cert"))
                .isEqualTo(withdrawn);
        assertThatThrownBy(() -> candidates.register(input, "first", "operator#cert"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> candidates.withdraw("source-1", "v1", 1, "new", "operator#cert"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_event", Integer.class)).isEqualTo(2);
        assertThat(service(db, source).read("source-1", "v1")).isEqualTo(withdrawn);
        script(source, "db/dm8/U036__product_source_candidates.sql");
    }

    @Test void failedEventInsertRollsBackCandidateAndReceipt() throws Exception {
        JdbcDataSource source = database();
        script(source, "db/dm8/V036__product_source_candidates.sql");
        JdbcTemplate db = new JdbcTemplate(source);
        db.execute("ALTER TABLE con4_source_event ADD CONSTRAINT ck_probe_fail CHECK (operation <> 'REGISTER')");
        var service = service(db, source);
        assertThatThrownBy(() -> service.register(declaration("source-1", "v1", "s1"),
                "request", "operator#cert"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_contract", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_request", Integer.class)).isZero();
    }

    private static ProductSourceCandidates service(JdbcTemplate db, JdbcDataSource source) {
        return new ProductSourceCandidates(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                new ObjectMapper().findAndRegisterModules(),
                new ProductRetentionPolicy("source-retention-approval", 1, "a".repeat(64),
                        Duration.ofDays(2)), Clock.systemUTC());
    }

    private static ProductSourceCandidates.Declaration declaration(String source, String version, String sourceVersion) {
        return declaration(source, version, sourceVersion, Map.of("title", List.of("QUERY")));
    }

    private static ProductSourceCandidates.Declaration declaration(String source, String version,
            String sourceVersion, Map<String, List<String>> uses) {
        return new ProductSourceCandidates.Declaration(source, version, sourceVersion,
                "recorded-origin", "recorded-owner", "evidence-ref", "b".repeat(64),
                Map.of("jurisdiction", List.of("CN")), List.of("title"), uses, List.of("title"),
                false, false, true, true, true, Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(3600));
    }

    private static JdbcDataSource database() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source-candidates-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return source;
    }

    private static void script(JdbcDataSource source, String path) throws Exception {
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(path)));
        }
    }
}
