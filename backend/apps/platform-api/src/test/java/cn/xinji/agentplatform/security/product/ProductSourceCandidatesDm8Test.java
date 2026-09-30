package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ProductSourceCandidatesDm8Test {
    @Test void isolatedDm8ForwardTransactionRestartAndReverse() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String admin = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && admin != null && password != null,
                "Isolated DM8 credentials are not configured");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String schema = "SC" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + HexFormat.of().formatHex(random) + "K7z";
        boolean created = false;
        try {
            try (var control = DriverManager.getConnection(url, admin, password);
                 var create = control.createStatement()) {
                create.execute("CREATE USER " + schema + " IDENTIFIED BY \"" + secret + "\"");
                created = true;
                create.execute("GRANT RESOURCE TO " + schema);
            }
            try (var connection = DriverManager.getConnection(url, schema, secret)) {
                try {
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                            Path.of("db/dm8/V036__product_source_candidates.sql")));
                    var source = new DriverManagerDataSource(url, schema, secret);
                    var db = new JdbcTemplate(source);
                    var retention = new ProductRetentionPolicy("isolated-policy", 1, "a".repeat(64),
                            Duration.ofHours(2));
                    var candidate = new ProductSourceCandidates.Declaration("source-1", "v1", "observed-1",
                            "recorded-origin", "recorded-owner", "evidence-ref", "b".repeat(64),
                            Map.of("jurisdiction", List.of("CN")), List.of("title"),
                            Map.of("title", List.of("QUERY")), List.of("title"),
                            false, false, true, true, true, Instant.now().minusSeconds(60),
                            Instant.now().plusSeconds(3600));
                    var service = new ProductSourceCandidates(db,
                            new TransactionTemplate(new DataSourceTransactionManager(source)),
                            new ObjectMapper().findAndRegisterModules(), retention, Clock.systemUTC());
                    assertThat(service.register(candidate, "register-1", "operator#certificate").status())
                            .isEqualTo("CANDIDATE");
                    var restarted = new ProductSourceCandidates(db,
                            new TransactionTemplate(new DataSourceTransactionManager(source)),
                            new ObjectMapper().findAndRegisterModules(), retention, Clock.systemUTC());
                    assertThat(restarted.withdraw("source-1", "v1", 1, "withdraw-1",
                            "operator#certificate").status()).isEqualTo("WITHDRAWN");
                    assertThat(db.queryForObject("SELECT COUNT(*) FROM con4_source_event", Integer.class))
                            .isEqualTo(2);
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                            Path.of("db/dm8/U036__product_source_candidates.sql")));
                } finally {
                    for (String table : List.of("con4_source_request", "con4_source_event",
                            "con4_source_contract")) {
                        try (var drop = connection.createStatement()) { drop.execute("DROP TABLE " + table); }
                        catch (SQLException absent) { /* A partial migration can leave an absent table. */ }
                    }
                }
            }
        } finally {
            if (created) {
                try (var control = DriverManager.getConnection(url, admin, password);
                     var drop = control.createStatement()) { drop.execute("DROP USER " + schema); }
            }
        }
    }
}
