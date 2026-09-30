package cn.xinji.agentplatform.enforcement.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceRuleStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class EvidenceRuleProductTest {
    @Test void draftUsesExactGrantTransactionReceiptAndImmutableRead() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:enf4" + System.nanoTime()
                + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/dm8/V050__enforcement_v2_evidence_rules.sql")));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/dm8/V051__enforcement_v2_evidence_publication.sql")));
        }
        var db = new JdbcTemplate(source);
        var store = new Dm8EvidenceRuleStore(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)), Clock.systemUTC());
        var permitted = new AtomicBoolean(true);
        var service = new EvidenceRuleService(store, (actor, org, id, version, action) -> {
            if (!permitted.get() || !"named-project-reviewer".equals(actor)
                    || !"ORG-ENF-PROJECT".equals(org) || !"RULE-001".equals(id)
                    || (version < 1 || version > 4) || !("EDIT_RULE".equals(action) ||
                    "READ_RULE".equals(action) || "PUBLISH_RULE".equals(action) || "DISABLE_RULE".equals(action)))
                throw new SecurityException("Exact current grant required");
        }, (type, ref, version, scope) -> {
            if (!"PROJECT_DOCUMENT_REVIEW".equals(type) || !"docs/57#6.24".equals(ref) ||
                    !"1".equals(version) || !"PROJECT-CASE-001".equals(scope))
                throw new SecurityException("Unapproved basis");
        });
        var input = new EvidenceRuleService.DraftInput("RULE-001", "ORG-ENF-PROJECT",
                "PROJECT_DOCUMENT_REVIEW", "docs/57#6.24", List.of(
                        new EvidenceRuleStore.Matter("ITEM_IDENTITY", true, "line-prefix:The submitted item is labeled "),
                        new EvidenceRuleStore.Matter("EVENT_TIME", true, "line-prefix:The observed event time is ")));
        var first = service.draft("named-project-reviewer", "evidence-rule-key", input);
        assertThat(service.draft("named-project-reviewer", "evidence-rule-key", input)).isEqualTo(first);
        assertThat(service.read("named-project-reviewer", "RULE-001", 1).state()).isEqualTo("DRAFT");
        assertThat(service.read("named-project-reviewer", "RULE-001", 1).matters()).hasSize(2);
        var manifest = new ObjectMapper().readTree(Path.of(
                "data/enforcement/evidence-v2-acceptance-v1/manifest.json").toFile());
        var refs = new java.util.ArrayList<EvidenceTrialSource.Ref>();
        manifest.path("materials").forEach(item -> refs.add(new EvidenceTrialSource.Ref(
                item.path("ref").asText(), item.path("version").asText(), item.path("sha256").asText())));
        var trial = new EvidenceRuleTrial(store, new FileEvidenceTrialSource(Path.of(
                "data/enforcement/evidence-v2-acceptance-v1"), new ObjectMapper()),
                (actor, org, type, id, version, action, purpose) -> {
                    if (!permitted.get() || !"named-project-reviewer".equals(actor) ||
                            !"ORG-ENF-PROJECT".equals(org)) throw new SecurityException("Current grant required");
                });
        var trialRequest = new EvidenceRuleTrial.Request("PROJECT-CASE-001", "1", refs);
        var trialResult = trial.run("named-project-reviewer", "RULE-001", 1, trialRequest);
        assertThat(trialResult.matters()).extracting(EvidenceRuleTrial.MatterResult::coverage)
                .containsExactly("CONFLICT", "PRESENT");
        assertThat(trialResult.matters().get(0).candidates()).hasSize(2);
        assertThat(trialResult.matters().get(1).candidates()).hasSize(2);
        assertThat(trialResult.unrelatedMaterials()).containsExactly("PROJECT-RECORD-C");
        assertThat(trial.run("named-project-reviewer", "RULE-001", 1,
                new EvidenceRuleTrial.Request("PROJECT-CASE-001", "1", List.of(refs.get(2))))
                .matters()).extracting(EvidenceRuleTrial.MatterResult::coverage)
                .containsExactly("MISSING", "MISSING");
        assertThatThrownBy(() -> trial.run("named-project-reviewer", "RULE-001", 1,
                new EvidenceRuleTrial.Request("PROJECT-CASE-001", "1", List.of(
                        new EvidenceTrialSource.Ref(refs.getFirst().id(), "1", "0".repeat(64))))))
                .isInstanceOf(SecurityException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_receipt", Integer.class)).isEqualTo(1);
        var review = new EvidenceRuleService.Publication("1", "PROJECT-CASE-001", "REVIEW-001",
                List.of(new EvidenceRuleService.Example("positive", List.of("ITEM_IDENTITY", "EVENT_TIME"), List.of())),
                List.of(new EvidenceRuleService.Example("negative", List.of("ITEM_IDENTITY"), List.of("EVENT_TIME"))));
        assertThatThrownBy(() -> service.publish("named-project-reviewer", "bad-review", "RULE-001",
                new EvidenceRuleService.Publication("1", "PROJECT-CASE-001", "REVIEW-001",
                        review.positive(), List.of(new EvidenceRuleService.Example("negative",
                        List.of("ITEM_IDENTITY"), List.of()))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.publish("named-project-reviewer", "wrong-basis", "RULE-001",
                new EvidenceRuleService.Publication("2", "PROJECT-CASE-001", "REVIEW-001",
                        review.positive(), review.negative()))).isInstanceOf(SecurityException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_publication", Integer.class)).isZero();
        var publication = service.publish("named-project-reviewer", "publish", "RULE-001", review);
        assertThat(publication.revision()).isEqualTo(2);
        assertThat(service.publish("named-project-reviewer", "publish", "RULE-001", review))
                .isEqualTo(publication);
        assertThat(service.read("named-project-reviewer", "RULE-001", 2).state()).isEqualTo("ACTIVE");
        assertThat(service.publication("named-project-reviewer", "RULE-001", 2).basisVersion()).isEqualTo("1");
        assertThat(service.publication("named-project-reviewer", "RULE-001", 2).reviewRef())
                .isEqualTo("REVIEW-001");
        assertThat(service.publication("named-project-reviewer", "RULE-001", 2).reviewDigest())
                .matches("[0-9a-f]{64}");
        assertThat(service.disable("named-project-reviewer", "disable", "RULE-001", 2).revision()).isEqualTo(2);
        assertThat(service.disable("named-project-reviewer", "disable", "RULE-001", 2).revision()).isEqualTo(2);
        assertThat(service.read("named-project-reviewer", "RULE-001", 2).state()).isEqualTo("DISABLED");
        assertThat(service.publication("named-project-reviewer", "RULE-001", 2).reviewRef())
                .isEqualTo("REVIEW-001");
        assertThat(service.read("named-project-reviewer", "RULE-001", 1).state()).isEqualTo("DRAFT");
        assertThatThrownBy(() -> service.disable("named-project-reviewer", "disable-again", "RULE-001", 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.draft("named-project-reviewer", "evidence-rule-key",
                new EvidenceRuleService.DraftInput("RULE-001", "ORG-ENF-PROJECT",
                        "OTHER_TYPE", "docs/57#6.24", input.matters())))
                .isInstanceOf(IllegalArgumentException.class);
        var revised = new EvidenceRuleService.DraftInput("RULE-001", "ORG-ENF-PROJECT",
                "PROJECT_DOCUMENT_REVIEW", "docs/57#6.24", List.of(
                new EvidenceRuleStore.Matter("ITEM_IDENTITY", true, "line-prefix:No such line "),
                new EvidenceRuleStore.Matter("EVENT_TIME", true, "line-prefix:The observed event time is ")));
        assertThat(service.draft("named-project-reviewer", "new-key", revised).revision()).isEqualTo(3);
        assertThat(service.draft("named-project-reviewer", "new-key", revised).revision()).isEqualTo(3);
        assertThat(service.read("named-project-reviewer", "RULE-001", 3).state()).isEqualTo("DRAFT");
        assertThat(service.read("named-project-reviewer", "RULE-001", 2).matters())
                .containsExactlyInAnyOrderElementsOf(input.matters());
        assertThat(trial.run("named-project-reviewer", "RULE-001", 3, trialRequest).matters())
                .extracting(EvidenceRuleTrial.MatterResult::coverage).containsExactly("CONFLICT", "MISSING");
        assertThat(trial.run("named-project-reviewer", "RULE-001", 2, trialRequest).matters())
                .extracting(EvidenceRuleTrial.MatterResult::coverage).containsExactly("CONFLICT", "PRESENT");
        assertThatThrownBy(() -> service.draft("named-project-reviewer", "duplicate-draft", revised))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.publish("named-project-reviewer", "new-bad-review", "RULE-001",
                new EvidenceRuleService.Publication("1", "PROJECT-CASE-001", "REVIEW-002",
                        review.positive(), List.of(new EvidenceRuleService.Example("negative",
                        List.of("ITEM_IDENTITY"), List.of()))))).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_rule", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_publication", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_receipt", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_event", Integer.class)).isEqualTo(4);
        var secondReview = new EvidenceRuleService.Publication("1", "PROJECT-CASE-001", "REVIEW-002",
                review.positive(), review.negative());
        assertThat(service.publish("named-project-reviewer", "publish-second", "RULE-001", secondReview)
                .revision()).isEqualTo(4);
        assertThat(service.publish("named-project-reviewer", "publish", "RULE-001", review))
                .isEqualTo(publication);
        assertThat(service.read("named-project-reviewer", "RULE-001", 4).state()).isEqualTo("ACTIVE");
        assertThat(service.read("named-project-reviewer", "RULE-001", 2).state()).isEqualTo("DISABLED");
        assertThat(service.publication("named-project-reviewer", "RULE-001", 2).reviewRef())
                .isEqualTo("REVIEW-001");
        assertThat(service.publication("named-project-reviewer", "RULE-001", 4).reviewRef())
                .isEqualTo("REVIEW-002");
        assertThatThrownBy(() -> service.read("named-project-reviewer", "MISSING", 1))
                .isInstanceOf(SecurityException.class);
        permitted.set(false);
        assertThatThrownBy(() -> trial.run("named-project-reviewer", "RULE-001", 1, trialRequest))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.draft("named-project-reviewer", "evidence-rule-key", input))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.read("named-project-reviewer", "RULE-001", 1))
                .isInstanceOf(SecurityException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_rule", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_publication", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_disabled", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_receipt", Integer.class)).isEqualTo(5);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_evidence_event", Integer.class)).isEqualTo(5);
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/dm8/U051__enforcement_v2_evidence_publication.sql")));
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/dm8/U050__enforcement_v2_evidence_rules.sql")));
        }
    }
}
