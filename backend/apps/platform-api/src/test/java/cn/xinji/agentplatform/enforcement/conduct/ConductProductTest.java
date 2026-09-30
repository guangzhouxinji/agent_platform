package cn.xinji.agentplatform.enforcement.conduct;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8ConductStore;
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

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;
import static org.junit.jupiter.api.Assertions.*;

class ConductProductTest {
    private final AtomicBoolean granted = new AtomicBoolean(true);
    private final ConductRuleService.Access ruleAccess = (actor, org, id, version, action) -> permit();
    private final ConductAnalysisService.Access reportAccess =
            (actor, org, type, id, version, action, purpose) -> permit();
    private final ConductAnalysisService.LockedEvidence evidence = new ConductAnalysisService.LockedEvidence() {
        @Override public void require(String actor, Input input) { permit(); }
        @Override public void require(String actor, Report report) { permit(); }
    };

    @Test void candidateCannotPublishAndNoHalfReceiptIsWritten() throws Exception {
        var store = store("candidate");
        var rules = new ConductRuleService(store, ruleAccess, (basis, behavior) -> {
            throw new SecurityException("Basis unapproved");
        });
        assertThrows(IllegalArgumentException.class, () -> rules.draft("author", "contradictory-key",
                new ConductRuleService.Draft("bad", "org", Behavior.PATENT_INFRINGEMENT,
                        null, List.of(new Element("x", "x", true)), List.of("DEFENSE"),
                        true, "purported no defense")));
        rules.draft("author", "draft-key", draft());
        var trial = rules.trial("author", "conduct-rule", 1, input());
        assertEquals(Outcome.INSUFFICIENT, trial.outcome());
        assertThrows(IllegalArgumentException.class, () -> rules.publish("author", "publish-key",
                "conduct-rule", 1, new ConductRuleService.Publication("review", List.of(), List.of())));
        assertTrue(store.rule("conduct-rule", 2).isEmpty());
        var db = jdbc("candidate");
        assertEquals(1, count(db, "enf7_conduct_receipt"));
        assertEquals(1, count(db, "enf7_conduct_event"));
        granted.set(false);
        assertThrows(SecurityException.class, () -> rules.read("author", "conduct-rule", 1));
        assertEquals(1, count(db, "enf7_conduct_receipt"));
    }

    @Test void reportVersionsAndReceiptsAreImmutableAcrossNamedReviewAndRerun() throws Exception {
        var store = store("reports");
        var rule = new Rule("conduct-rule", 1, "org", Behavior.TRADEMARK_INFRINGEMENT, "ACTIVE",
                new Basis("project-basis", "1", "section 1", "VERIFIED", "scope", "district", "procedure"),
                List.of(new Element("BEHAVIOR_FACT", "behavior", true)), List.of("DEFENSE"),
                false, null, "review", "author");
        store.command("rule-key", "rule-hash", "author", "SEED_RULE", () -> {
            store.addRule(rule);
            return new Receipt("rule-key", "SEED_RULE", rule.id(), rule.version());
        });
        var analyses = new ConductAnalysisService(store, reportAccess, evidence);
        var first = analyses.analyze("analyst", "analyze-key", input());
        assertEquals(first, analyses.analyze("analyst", "analyze-key", input()));
        var original = input();
        var changed = new Input(original.id(), original.organization(), original.caseRef(),
                original.caseVersion(), original.evidenceOrganizationId(),
                original.evidenceOrganizationVersion(), original.ruleId(), original.ruleVersion(),
                original.behavior(), original.behaviorAt(), original.right(), original.facts(),
                original.links(), List.of("NEW-DEFENSE@1"));
        assertThrows(IllegalArgumentException.class,
                () -> analyses.analyze("analyst", "analyze-key", changed));
        assertEquals(2, count(jdbc("reports"), "enf7_conduct_receipt"));
        assertEquals(Outcome.INSUFFICIENT, analyses.read("analyst", "analysis", 1).outcome());
        assertEquals(Applicability.UNKNOWN, analyses.read("analyst", "analysis", 1).scope().state());
        assertTrue(analyses.read("analyst", "analysis", 1).gaps().contains("APPLICABILITY_SCOPE_UNKNOWN"));
        assertEquals("MACHINE_CANDIDATE", analyses.read("analyst", "analysis", 1).facts().getFirst().kind());
        analyses.review("reviewer", "review-key", "analysis",
                new ConductAnalysisService.Review(1, "Needs historical status", "RETURNED"));
        var revised = analyses.rerun("analyst", "rerun-key", "analysis",
                new ConductAnalysisService.Rerun(2, input()));
        assertEquals(3, revised.version());
        assertEquals("RETURNED", analyses.read("analyst", "analysis", 2).reviewState());
        assertEquals("REVIEW_REQUIRED", analyses.read("analyst", "analysis", 3).reviewState());
        assertEquals("reviewer", analyses.read("analyst", "analysis", 2).reviewedBy());
        assertEquals(analyses.read("analyst", "analysis", 1).facts(),
                analyses.read("analyst", "analysis", 2).facts());
        assertThrows(IllegalArgumentException.class, () -> analyses.review("reviewer", "stale-key",
                "analysis", new ConductAnalysisService.Review(2, "late", "REVIEWED")));
        var db = jdbc("reports");
        assertEquals(3, count(db, "enf7_conduct_report"));
        assertEquals(4, count(db, "enf7_conduct_receipt"));
        assertEquals(4, count(db, "enf7_conduct_event"));
        granted.set(false);
        assertThrows(SecurityException.class, () -> analyses.read("analyst", "analysis", 3));
        assertThrows(SecurityException.class, () -> analyses.rerun("analyst", "denied-key",
                "analysis", new ConductAnalysisService.Rerun(3, input())));
        assertEquals(4, count(db, "enf7_conduct_receipt"));
        assertEquals(3, count(db, "enf7_conduct_report"));
    }

    @Test void exactApprovedBasisAndBoundaryExamplesPublishThenDisable() throws Exception {
        var store = store("publication");
        var rules = new ConductRuleService(store, ruleAccess, (basis, behavior) -> {
            if (!"PROJECT-APPROVED".equals(basis.ref()) ||
                    behavior != Behavior.COUNTERFEIT_PATENT)
                throw new SecurityException("Wrong scoped basis");
        });
        var basis = new Basis("PROJECT-APPROVED", "1", "section 1", "VERIFIED",
                "project-only", "project-area", "counterfeit-procedure");
        rules.draft("author", "draft-key", new ConductRuleService.Draft("conduct-rule", "org",
                Behavior.COUNTERFEIT_PATENT, basis,
                List.of(new Element("REPRESENTATION", "representation", true)),
                List.of(), true, "project-review-of-no-defense"));
        var positive = new Input("analysis", "org", "case", "1", "evidence", 1,
                "conduct-rule", 1, Behavior.COUNTERFEIT_PATENT, "2026-09-14",
                new RightAtTime("right", "1", "VERIFIED_AT_BEHAVIOR_TIME", "2026-09-14", "project-source"),
                List.of(new Fact("fact", "HUMAN_CONFIRMED", "material", "line 3")),
                List.of(new Link("REPRESENTATION", "fact", "material", "line 3", Relation.SUPPORT)),
                List.of());
        var negative = new Input("other", "org", "case", "1", "evidence", 1,
                "conduct-rule", 1, Behavior.COUNTERFEIT_PATENT, "2026-09-14", null,
                positive.facts(), List.of(), List.of());
        var publication = new ConductRuleService.Publication("named-project-review",
                List.of(new ConductRuleService.Example(positive, Outcome.SUSPECTED, List.of())),
                List.of(new ConductRuleService.Example(negative, Outcome.INSUFFICIENT,
                        List.of("ELEMENT:REPRESENTATION:MISSING", "RIGHT_AT_BEHAVIOR_TIME_UNKNOWN"))));
        assertEquals(2, rules.publish("author", "publish-key", "conduct-rule", 1, publication).version());
        assertEquals("ACTIVE", rules.read("author", "conduct-rule", 2).state());
        assertEquals(2, rules.disable("author", "disable-key", "conduct-rule", 2).version());
        assertEquals("DISABLED", rules.read("author", "conduct-rule", 2).state());
        assertEquals(3, count(jdbc("publication"), "enf7_conduct_receipt"));
        assertEquals(3, count(jdbc("publication"), "enf7_conduct_event"));
    }

    @Test void upstreamFailureAndDisabledRuleLeaveNoAnalysisOrReceipt() throws Exception {
        var store = store("source-failure");
        var active = new Rule("conduct-rule", 1, "org", Behavior.TRADEMARK_INFRINGEMENT, "ACTIVE",
                new Basis("project", "1", "section 1", "VERIFIED", "scope", "area", "procedure"),
                List.of(new Element("BEHAVIOR_FACT", "behavior", true)), List.of(),
                true, "project review", "named-review", "author");
        store.command("rule-key", "rule-hash", "author", "PUBLISH_CONDUCT_RULE", () -> {
            store.addRule(active);
            return new Receipt("rule-key", "PUBLISH_CONDUCT_RULE", "conduct-rule", 1);
        });
        var unavailable = new ConductAnalysisService(store, reportAccess,
                new ConductAnalysisService.LockedEvidence() {
                    @Override public void require(String actor, Input input) {
                        throw new IllegalStateException("Original source unavailable");
                    }
                    @Override public void require(String actor, Report report) {
                        throw new IllegalStateException("Original source unavailable");
                    }
                });
        assertThrows(IllegalStateException.class,
                () -> unavailable.analyze("analyst", "failed-source", input()));
        assertEquals(0, store.latestReportVersion("analysis"));
        assertEquals(1, count(jdbc("source-failure"), "enf7_conduct_receipt"));
        assertEquals(1, count(jdbc("source-failure"), "enf7_conduct_event"));
        store.command("disable-key", "disable-hash", "author", "DISABLE_CONDUCT_RULE", () -> {
            store.disable("conduct-rule", 1);
            return new Receipt("disable-key", "DISABLE_CONDUCT_RULE", "conduct-rule", 1);
        });
        var analyses = new ConductAnalysisService(store, reportAccess, evidence);
        assertThrows(SecurityException.class,
                () -> analyses.analyze("analyst", "disabled-analysis", input()));
        assertEquals(0, count(jdbc("source-failure"), "enf7_conduct_report"));
        assertEquals(2, count(jdbc("source-failure"), "enf7_conduct_receipt"));
    }

    @Test void wrongBehaviorBranchCannotUsePublishedRuleOrWriteReceipt() throws Exception {
        var store = store("wrong-branch");
        store.command("rule-key", "rule-hash", "author", "PUBLISH_CONDUCT_RULE", () -> {
            store.addRule(new Rule("conduct-rule", 1, "org", Behavior.COUNTERFEIT_PATENT,
                    "ACTIVE", new Basis("project", "1", "section 1", "VERIFIED", "scope",
                            "area", "counterfeit-procedure"),
                    List.of(new Element("RIGHT_REPRESENTATION", "representation", true)),
                    List.of(), true, "project review", "named-review", "author"));
            return new Receipt("rule-key", "PUBLISH_CONDUCT_RULE", "conduct-rule", 1);
        });
        var analyses = new ConductAnalysisService(store, reportAccess, evidence);
        assertThrows(SecurityException.class,
                () -> analyses.analyze("analyst", "wrong-branch-key", input()));
        assertEquals(0, store.latestReportVersion("analysis"));
        assertEquals(1, count(jdbc("wrong-branch"), "enf7_conduct_receipt"));
        assertEquals(1, count(jdbc("wrong-branch"), "enf7_conduct_event"));
    }

    @Test void caseScopeMismatchNeverProducesSuspectedOutcome() throws Exception {
        var store = store("scope-mismatch");
        store.command("rule-key", "rule-hash", "author", "PUBLISH_CONDUCT_RULE", () -> {
            store.addRule(new Rule("conduct-rule", 1, "org", Behavior.TRADEMARK_INFRINGEMENT,
                    "ACTIVE", new Basis("project", "1", "section 1", "VERIFIED", "scope",
                            "area", "procedure"), List.of(new Element("BEHAVIOR_FACT", "behavior", true)),
                    List.of(), true, "project review", "named-review", "author"));
            return new Receipt("rule-key", "PUBLISH_CONDUCT_RULE", "conduct-rule", 1);
        });
        var analyses = new ConductAnalysisService(store, reportAccess, evidence,
                (actor, input, rule) -> new ScopeVerification(Applicability.MISMATCH, "case", "1"));
        analyses.analyze("analyst", "analysis-key", input());
        var report = analyses.read("analyst", "analysis", 1);
        assertEquals(Outcome.INSUFFICIENT, report.outcome());
        assertEquals(Applicability.MISMATCH, report.scope().state());
        assertTrue(report.gaps().contains("APPLICABILITY_SCOPE_MISMATCH"));
        assertEquals(1, count(jdbc("scope-mismatch"), "enf7_conduct_report"));
        assertEquals(2, count(jdbc("scope-mismatch"), "enf7_conduct_receipt"));
    }

    @Test void caseScopeFailureLeavesNoReportEventOrReceipt() throws Exception {
        var store = store("scope-failure");
        store.command("rule-key", "rule-hash", "author", "PUBLISH_CONDUCT_RULE", () -> {
            store.addRule(new Rule("conduct-rule", 1, "org", Behavior.TRADEMARK_INFRINGEMENT,
                    "ACTIVE", new Basis("project", "1", "section 1", "VERIFIED", "scope",
                            "area", "procedure"), List.of(new Element("BEHAVIOR_FACT", "behavior", true)),
                    List.of(), true, "project review", "named-review", "author"));
            return new Receipt("rule-key", "PUBLISH_CONDUCT_RULE", "conduct-rule", 1);
        });
        var analyses = new ConductAnalysisService(store, reportAccess, evidence,
                (actor, input, rule) -> { throw new IllegalStateException("Case snapshot unavailable"); });
        assertThrows(IllegalStateException.class,
                () -> analyses.analyze("analyst", "analysis-key", input()));
        assertEquals(0, count(jdbc("scope-failure"), "enf7_conduct_report"));
        assertEquals(1, count(jdbc("scope-failure"), "enf7_conduct_event"));
        assertEquals(1, count(jdbc("scope-failure"), "enf7_conduct_receipt"));
    }

    private void permit() { if (!granted.get()) throw new SecurityException("Current grant lost"); }

    private static ConductRuleService.Draft draft() {
        return new ConductRuleService.Draft("conduct-rule", "org", Behavior.TRADEMARK_INFRINGEMENT,
                new Basis("candidate", "1", "section 1", "UNVERIFIED", "scope", "district", "procedure"),
                List.of(new Element("BEHAVIOR_FACT", "behavior", true)), List.of("DEFENSE"), false, null);
    }

    private static Input input() {
        return new Input("analysis", "org", "case", "1", "evidence", 1,
                "conduct-rule", 1, Behavior.TRADEMARK_INFRINGEMENT, "2026-09-14", null,
                List.of(new Fact("fact", "MACHINE_CANDIDATE", "material", "line 3")),
                List.of(new Link("BEHAVIOR_FACT", "fact", "material", "line 3", Relation.SUPPORT)),
                List.of());
    }

    private static String url(String name) { return "jdbc:h2:mem:conduct-" + name + ";MODE=Oracle;DB_CLOSE_DELAY=-1"; }
    private static JdbcTemplate jdbc(String name) {
        return new JdbcTemplate(new DriverManagerDataSource(url(name), "sa", ""));
    }
    private static Dm8ConductStore store(String name) throws Exception {
        var source = new DriverManagerDataSource(url(name), "sa", "");
        try (var connection = source.getConnection()) {
            for (String migration : List.of("V035__enforcement_v2_material.sql",
                    "V050__enforcement_v2_evidence_rules.sql",
                    "V051__enforcement_v2_evidence_publication.sql",
                    "V052__enforcement_v2_evidence_organizations.sql",
                    "V060__enforcement_v2_conduct.sql"))
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8", migration)));
        }
        var db = new JdbcTemplate(source);
        db.update("INSERT INTO enf4_evidence_rule(rule_id,revision,organization_id,case_type,state,created_by,created_at) "
                + "VALUES('evidence-rule',1,'org','case','ACTIVE','author',CURRENT_TIMESTAMP)");
        db.update("INSERT INTO enf4_organization(organization_id,revision,owner_organization,case_ref,"
                + "case_version,rule_id,rule_revision,review_state,payload,created_by,created_at) "
                + "VALUES('evidence',1,'org','case','1','evidence-rule',1,'REVIEWED','{}','author',CURRENT_TIMESTAMP)");
        return new Dm8ConductStore(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                new ObjectMapper(), Clock.systemUTC());
    }
    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
