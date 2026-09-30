package cn.xinji.agentplatform.enforcement.procedure;

import static cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.*;
import static org.assertj.core.api.Assertions.*;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8ProcedureStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ProcedureDm8Test {
    @Test void isolatedDm8PersistsExactVersionsAndAtomicReceiptsAcrossConnections() throws Exception {
        String url = System.getenv("ENF_DEV_DB_URL");
        String password = System.getenv("ENF_DEV_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && password != null, "Dedicated enforcement DM8 required");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String owner = "EP" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + owner + "K7z";
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE USER " + owner + " IDENTIFIED BY \"" + secret + "\"");
            statement.execute("GRANT RESOURCE TO " + owner);
        }
        try {
            var data = new DriverManagerDataSource(url, owner, secret);
            try (var connection = data.getConnection()) {
                ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource(Path.of("db/dm8/V059__enforcement_v2_procedure.sql")));
            }
            try {
                var json = new ObjectMapper().findAndRegisterModules();
                var first = store(data, json);
                var rule = new Rule("PROJECT-RULE", 1, owner, "PROJECT_TEST", "REVIEW", "STAGE", "DRAFT",
                        null, null, List.of("EVENT"), List.of("FILE@1"), "named", null, null);
                var receipt = first.command("draft-1", "digest-1", "named", "DRAFT_PROCEDURE_RULE", () -> {
                    first.addRule(rule);
                    return new Receipt("draft-1", "DRAFT_PROCEDURE_RULE", rule.id(), 1);
                });
                var second = store(new DriverManagerDataSource(url, owner, secret), json);
                assertThat(second.command("draft-1", "digest-1", "named", "DRAFT_PROCEDURE_RULE", () -> {
                    throw new AssertionError("Replay cannot write");
                })).isEqualTo(receipt);
                assertThat(second.rule(rule.id(), 1)).contains(rule);
                assertThatThrownBy(() -> second.command("failed-1", "digest-2", "named",
                        "DRAFT_PROCEDURE_RULE", () -> {
                            second.addRule(new Rule("ROLLED-BACK", 1, owner, "PROJECT_TEST", "REVIEW", "STAGE",
                                    "DRAFT", null, null, List.of(), List.of(), "named", null, null));
                            throw new IllegalArgumentException("Validation failed");
                        })).isInstanceOf(IllegalArgumentException.class);
                var db = new JdbcTemplate(data);
                assertThat(second.rule("ROLLED-BACK", 1)).isEmpty();
                var start = new CountDownLatch(1);
                var left = CompletableFuture.supplyAsync(() -> concurrentDraft(first, owner, start));
                var right = CompletableFuture.supplyAsync(() -> concurrentDraft(second, owner, start));
                start.countDown();
                assertThat(left.get()).isEqualTo(right.get());
                assertThat(second.rule("CONCURRENT", 1)).isPresent();
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(2);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_event", Integer.class)).isEqualTo(2);
            } finally {
                try (var connection = data.getConnection()) {
                    ScriptUtils.executeSqlScript(connection,
                            new FileSystemResource(Path.of("db/dm8/U059__enforcement_v2_procedure.sql")));
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
                 var statement = connection.createStatement()) {
                statement.execute("DROP USER " + owner);
            }
        }
    }
    private Dm8ProcedureStore store(DriverManagerDataSource data, ObjectMapper json) {
        return new Dm8ProcedureStore(new JdbcTemplate(data),
                new TransactionTemplate(new DataSourceTransactionManager(data)), json, Clock.systemUTC());
    }
    private Receipt concurrentDraft(Dm8ProcedureStore store, String owner, CountDownLatch start) {
        try { start.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        return store.command("concurrent-key", "concurrent-digest", "named", "DRAFT_PROCEDURE_RULE", () -> {
            store.addRule(new Rule("CONCURRENT", 1, owner, "PROJECT_TEST", "REVIEW", "STAGE",
                    "DRAFT", null, null, List.of(), List.of(), "named", null, null));
            return new Receipt("concurrent-key", "DRAFT_PROCEDURE_RULE", "CONCURRENT", 1);
        });
    }
}
