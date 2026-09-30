package cn.xinji.agentplatform.enforcement.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceRuleStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class EvidenceRuleDm8Test {
    @Test void independentDm8ConnectionsKeepDraftReceiptAndVersion() throws Exception {
        String url = System.getenv("ENF_DEV_DB_URL");
        String password = System.getenv("ENF_DEV_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && password != null, "Dedicated ENF DM8 required");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String owner = "EE" + HexFormat.of().formatHex(random).toUpperCase();
        String secret = "T9a" + owner + "K7z";
        try (var connection = DriverManager.getConnection(url, "SYSDBA", password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE USER " + owner + " IDENTIFIED BY \"" + secret + "\"");
            statement.execute("GRANT RESOURCE TO " + owner);
        }
        try {
            var source = new DriverManagerDataSource(url, owner, secret);
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource(Path.of("db/dm8/V050__enforcement_v2_evidence_rules.sql")));
                ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource(Path.of("db/dm8/V051__enforcement_v2_evidence_publication.sql")));
            }
            try {
                var first = new Dm8EvidenceRuleStore(new JdbcTemplate(source),
                        new TransactionTemplate(new DataSourceTransactionManager(source)), Clock.systemUTC());
                var service = new EvidenceRuleService(first, (actor, org, id, version, action) -> {
                    if (!"project-reviewer".equals(actor) || !"ORG-ENF-PROJECT".equals(org))
                        throw new SecurityException("Exact scope required");
                }, (type, ref, version, scope) -> {
                    if (!"1".equals(version)) throw new SecurityException("Basis unavailable");
                });
                var input = new EvidenceRuleService.DraftInput("RULE-001", "ORG-ENF-PROJECT",
                        "PROJECT_DOCUMENT_REVIEW", "docs/57#6.24", List.of(
                        new EvidenceRuleStore.Matter("ITEM_IDENTITY", true, "line-prefix:The submitted item is labeled "),
                        new EvidenceRuleStore.Matter("EVENT_TIME", true, "line-prefix:The observed event time is ")));
                service.draft("project-reviewer", "dm8-draft-key", input);
                var manifest = new ObjectMapper().readTree(Path.of(
                        "data/enforcement/evidence-v2-acceptance-v1/manifest.json").toFile());
                var refs = new java.util.ArrayList<EvidenceTrialSource.Ref>();
                manifest.path("materials").forEach(item -> refs.add(new EvidenceTrialSource.Ref(
                        item.path("ref").asText(), item.path("version").asText(), item.path("sha256").asText())));
                var trialRequest = new EvidenceRuleTrial.Request("PROJECT-CASE-001", "1", refs);
                var trial = new EvidenceRuleTrial(first, new FileEvidenceTrialSource(Path.of(
                        "data/enforcement/evidence-v2-acceptance-v1"), new ObjectMapper()),
                        (actor, org, type, id, version, action, purpose) -> { });
                assertThat(trial.run("project-reviewer", "RULE-001", 1, trialRequest).matters())
                        .extracting(EvidenceRuleTrial.MatterResult::coverage)
                        .containsExactly("CONFLICT", "PRESENT");
                assertThat(new JdbcTemplate(source).queryForObject(
                        "SELECT COUNT(*) FROM enf4_evidence_receipt", Integer.class)).isEqualTo(1);
                var review = new EvidenceRuleService.Publication("1", "PROJECT-CASE-001", "REVIEW-001",
                        List.of(new EvidenceRuleService.Example("positive",
                                List.of("ITEM_IDENTITY", "EVENT_TIME"), List.of())),
                        List.of(new EvidenceRuleService.Example("negative",
                                List.of("ITEM_IDENTITY"), List.of("EVENT_TIME"))));
                service.publish("project-reviewer", "dm8-publish", "RULE-001", review);
                var secondSource = new DriverManagerDataSource(url, owner, secret);
                var second = new Dm8EvidenceRuleStore(new JdbcTemplate(secondSource),
                        new TransactionTemplate(new DataSourceTransactionManager(secondSource)), Clock.systemUTC());
                var restarted = new EvidenceRuleService(second, (actor, org, id, version, action) -> {
                    if (!"project-reviewer".equals(actor)) throw new SecurityException("Current grant required");
                }, (type, ref, version, scope) -> {
                    if (!"1".equals(version)) throw new SecurityException("Basis unavailable");
                });
                assertThat(restarted.draft("project-reviewer", "dm8-draft-key", input).resultId())
                        .isEqualTo("RULE-001");
                assertThat(restarted.read("project-reviewer", "RULE-001", 1).matters()).hasSize(2);
                assertThat(restarted.publish("project-reviewer", "dm8-publish", "RULE-001", review).revision())
                        .isEqualTo(2);
                assertThat(restarted.read("project-reviewer", "RULE-001", 2).state()).isEqualTo("ACTIVE");
                assertThat(restarted.publication("project-reviewer", "RULE-001", 2).scope())
                        .isEqualTo("PROJECT-CASE-001");
                restarted.disable("project-reviewer", "dm8-disable", "RULE-001", 2);
                assertThat(service.read("project-reviewer", "RULE-001", 2).state()).isEqualTo("DISABLED");
                assertThat(service.publication("project-reviewer", "RULE-001", 2).reviewRef())
                        .isEqualTo("REVIEW-001");
                assertThatThrownBy(() -> restarted.draft("project-reviewer", "dm8-draft-key",
                        new EvidenceRuleService.DraftInput("RULE-001", "ORG-ENF-PROJECT",
                                "OTHER_TYPE", "docs/57#6.24", input.matters())))
                        .isInstanceOf(IllegalArgumentException.class);
                var changed = new EvidenceRuleService.DraftInput("RULE-001", "ORG-ENF-PROJECT",
                        "PROJECT_DOCUMENT_REVIEW", "docs/57#6.24", List.of(
                        new EvidenceRuleStore.Matter("ITEM_IDENTITY", true, "line-prefix:No such line "),
                        new EvidenceRuleStore.Matter("EVENT_TIME", true,
                                "line-prefix:The observed event time is ")));
                assertThat(restarted.draft("project-reviewer", "dm8-draft-second", changed).revision())
                        .isEqualTo(3);
                assertThat(trial.run("project-reviewer", "RULE-001", 2, trialRequest).matters())
                        .extracting(EvidenceRuleTrial.MatterResult::coverage)
                        .containsExactly("CONFLICT", "PRESENT");
                assertThat(trial.run("project-reviewer", "RULE-001", 3, trialRequest).matters())
                        .extracting(EvidenceRuleTrial.MatterResult::coverage)
                        .containsExactly("CONFLICT", "MISSING");
                assertThatThrownBy(() -> restarted.publish("project-reviewer", "dm8-bad-second",
                        "RULE-001", new EvidenceRuleService.Publication("2", "PROJECT-CASE-001",
                                "REVIEW-002", review.positive(), review.negative())))
                        .isInstanceOf(SecurityException.class);
                var db = new JdbcTemplate(secondSource);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_rule", Integer.class)).isEqualTo(3);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_publication", Integer.class)).isEqualTo(1);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_receipt", Integer.class)).isEqualTo(4);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_event", Integer.class)).isEqualTo(4);
                assertThat(restarted.publish("project-reviewer", "dm8-publish-second", "RULE-001",
                        new EvidenceRuleService.Publication("1", "PROJECT-CASE-001", "REVIEW-002",
                                review.positive(), review.negative())).revision()).isEqualTo(4);
                assertThat(restarted.read("project-reviewer", "RULE-001", 2).state()).isEqualTo("DISABLED");
                assertThat(restarted.publication("project-reviewer", "RULE-001", 2).reviewRef())
                        .isEqualTo("REVIEW-001");
                assertThat(restarted.read("project-reviewer", "RULE-001", 4).state()).isEqualTo("ACTIVE");
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_receipt", Integer.class)).isEqualTo(5);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_event", Integer.class)).isEqualTo(5);
            } finally {
                try (var connection = source.getConnection()) {
                    ScriptUtils.executeSqlScript(connection,
                            new FileSystemResource(Path.of("db/dm8/U051__enforcement_v2_evidence_publication.sql")));
                    ScriptUtils.executeSqlScript(connection,
                            new FileSystemResource(Path.of("db/dm8/U050__enforcement_v2_evidence_rules.sql")));
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
