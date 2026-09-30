package cn.xinji.agentplatform.enforcement.conduct;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8ConductStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;
import static org.junit.jupiter.api.Assertions.*;

class ConductDm8Test {
    @Test void isolatedDm8MigratesAndPreservesReportReceiptAndEvent() throws Exception {
        String url = System.getenv("ENF_DEV_DB_URL");
        String password = System.getenv("ENF_DEV_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && password != null, "Dedicated ENF DM8 required");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String owner = "EC" + HexFormat.of().formatHex(random).toUpperCase();
        String credential = "T9a" + owner + "K7z";
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE USER " + owner + " IDENTIFIED BY \"" + credential + "\"");
            statement.execute("GRANT RESOURCE TO " + owner);
        }
        try {
            var source = new DriverManagerDataSource(url, owner, credential);
            try (var connection = source.getConnection()) {
                for (String script : List.of("V035__enforcement_v2_material.sql",
                        "V050__enforcement_v2_evidence_rules.sql",
                        "V051__enforcement_v2_evidence_publication.sql",
                        "V052__enforcement_v2_evidence_organizations.sql",
                        "V060__enforcement_v2_conduct.sql"))
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8", script)));
            }
            try {
                var db = new JdbcTemplate(source);
                db.update("INSERT INTO enf4_evidence_rule(rule_id,revision,organization_id,case_type,"
                        + "state,created_by,created_at) VALUES('evidence-rule',1,'org','case','ACTIVE',"
                        + "'author',CURRENT_TIMESTAMP)");
                db.update("INSERT INTO enf4_organization(organization_id,revision,owner_organization,"
                        + "case_ref,case_version,rule_id,rule_revision,review_state,payload,created_by,"
                        + "created_at) VALUES('evidence',1,'org','case','1','evidence-rule',1,"
                        + "'REVIEWED','{}','author',CURRENT_TIMESTAMP)");
                var store = new Dm8ConductStore(db,
                        new TransactionTemplate(new DataSourceTransactionManager(source)),
                        new ObjectMapper(), Clock.systemUTC());
                var rule = new Rule("rule", 1, "org", Behavior.PATENT_INFRINGEMENT, "ACTIVE",
                        new Basis("project", "1", "section 1", "VERIFIED", "scope", "district", "procedure"),
                        List.of(new Element("RIGHT", "historical right", true)), List.of("DEFENSE"),
                        false, null, "named-review", "author");
                store.command("rule-key", "fingerprint", "author", "PUBLISH_CONDUCT_RULE", () -> {
                    store.addRule(rule);
                    return new Receipt("rule-key", "PUBLISH_CONDUCT_RULE", "rule", 1);
                });
                var analyses = new ConductAnalysisService(store,
                        (actor, org, type, id, version, action, purpose) -> { },
                        new ConductAnalysisService.LockedEvidence() {
                            @Override public void require(String actor, Input input) { }
                            @Override public void require(String actor, Report report) { }
                        });
                var input = new Input("analysis", "org", "case", "1", "evidence", 1,
                        "rule", 1, Behavior.PATENT_INFRINGEMENT, "2026-09-14", null,
                        List.of(), List.of(), List.of());
                var second = new DriverManagerDataSource(url, owner, credential);
                var secondStore = new Dm8ConductStore(new JdbcTemplate(second),
                        new TransactionTemplate(new DataSourceTransactionManager(second)),
                        new ObjectMapper(), Clock.systemUTC());
                var secondAnalysis = new ConductAnalysisService(secondStore,
                        (actor, org, type, id, version, action, purpose) -> { },
                        new ConductAnalysisService.LockedEvidence() {
                            @Override public void require(String actor, Input locked) { }
                            @Override public void require(String actor, Report report) { }
                        });
                var ready = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                try (var workers = Executors.newFixedThreadPool(2)) {
                    var first = workers.submit(() -> {
                        ready.countDown();
                        start.await(10, TimeUnit.SECONDS);
                        return analyses.analyze("analyst", "analyze-key", input);
                    });
                    var replay = workers.submit(() -> {
                        ready.countDown();
                        start.await(10, TimeUnit.SECONDS);
                        return secondAnalysis.analyze("analyst", "analyze-key", input);
                    });
                    assertTrue(ready.await(10, TimeUnit.SECONDS));
                    start.countDown();
                    assertEquals(first.get(20, TimeUnit.SECONDS), replay.get(20, TimeUnit.SECONDS));
                }
                assertEquals(Outcome.INSUFFICIENT, analyses.read("analyst", "analysis", 1).outcome());
                assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM enf7_conduct_receipt", Integer.class));
                assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM enf7_conduct_event", Integer.class));
                assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM enf7_conduct_report", Integer.class));
            } finally {
                try (var connection = source.getConnection()) {
                    for (String script : List.of("U060__enforcement_v2_conduct.sql",
                            "U052__enforcement_v2_evidence_organizations.sql",
                            "U051__enforcement_v2_evidence_publication.sql",
                            "U050__enforcement_v2_evidence_rules.sql",
                            "U035__enforcement_v2_material.sql"))
                        ScriptUtils.executeSqlScript(connection,
                                new FileSystemResource(Path.of("db/dm8", script)));
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
                 var statement = connection.createStatement()) {
                statement.execute("DROP USER " + owner);
            }
        }
    }
}
