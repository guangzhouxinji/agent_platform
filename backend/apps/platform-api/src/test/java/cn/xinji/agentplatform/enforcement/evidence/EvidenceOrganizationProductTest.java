package cn.xinji.agentplatform.enforcement.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceOrganizationStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceRuleStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8MaterialRepository;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialAccess;
import cn.xinji.agentplatform.enforcement.material.MaterialCollectionService;
import cn.xinji.agentplatform.enforcement.material.MaterialProductService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class EvidenceOrganizationProductTest {
    @TempDir Path temporary;

    @Test void verifiedProductMaterialsCreateExactCandidateRelationsWithoutOfficialFacts() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:enf4-org-" + System.nanoTime()
                + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = source.getConnection()) {
            for (String migration : List.of("V035__enforcement_v2_material.sql",
                    "V050__enforcement_v2_evidence_rules.sql",
                    "V051__enforcement_v2_evidence_publication.sql",
                    "V052__enforcement_v2_evidence_organizations.sql"))
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8", migration)));
        }
        exercise(source, temporary);
    }

    static void exercise(DriverManagerDataSource source, Path temporary) throws Exception {
        var db = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var materialStore = new Dm8MaterialRepository(db, tx, Clock.systemUTC());
        var files = new LocalMaterialFiles(temporary.resolve("files"));
        var allowed = new AtomicBoolean(true);
        MaterialAccess access = new MaterialAccess() {
            @Override public void require(String actor, String org, String type, String id,
                                          long version, String action, String purpose) {
                if (!allowed.get()) throw new SecurityException("Current grant revoked");
            }
            @Override public void requireSource(String id, String use, long epoch) {
                if (!allowed.get()) throw new SecurityException("Source withdrawn");
            }
            @Override public void prepareSource(String id, long epoch) { }
            @Override public void activateSource(String id, long epoch) { }
        };
        var material = new MaterialProductService(materialStore, files, access, Clock.systemUTC());
        String actor = "named-evidence-collector";
        material.registerSource(actor, "source", new MaterialProductService.SourceInput(
                "EVIDENCE-SOURCE", "ORG-ENF-PROJECT", "PROJECT-CASE-001", 1,
                Instant.now().plusSeconds(3600), List.of("QUERY", "SAVE", "DISPLAY", "ANALYZE"),
                "approved-source-ref"));
        var positive = material.observe(actor, "positive", new MaterialProductService.ObservationInput(
                "EVIDENCE-SOURCE", "POS", "1", "record positive", null));
        var negative = material.observe(actor, "negative", new MaterialProductService.ObservationInput(
                "EVIDENCE-SOURCE", "NEG", "1", "missing", null));
        material.draftRule(actor, "discovery-draft", new MaterialProductService.RuleInput(
                "EVIDENCE-DISCOVERY", "EVIDENCE-SOURCE", "record", "project-method"));
        material.ruleState(actor, "discovery-publish", "EVIDENCE-DISCOVERY", 1,
                "PUBLISH_RULE", positive.resultId(), negative.resultId());
        var ruleStore = new Dm8EvidenceRuleStore(db, tx, Clock.systemUTC());
        var ruleService = new EvidenceRuleService(ruleStore, (a, o, id, version, action) -> { },
                (type, ref, version, scope) -> { });
        ruleService.draft(actor, "rule-draft", new EvidenceRuleService.DraftInput("RULE-ORG",
                "ORG-ENF-PROJECT", "PROJECT_DOCUMENT_REVIEW", "docs/57#6.24", List.of(
                new EvidenceRuleStore.Matter("ITEM_IDENTITY", true,
                        "line-prefix:The submitted item is labeled "),
                new EvidenceRuleStore.Matter("EVENT_TIME", true,
                        "line-prefix:The observed event time is "))));
        ruleService.publish(actor, "rule-publish", "RULE-ORG", new EvidenceRuleService.Publication(
                "1", "PROJECT-CASE-001", "REVIEW-ORG", List.of(new EvidenceRuleService.Example(
                "positive", List.of("ITEM_IDENTITY", "EVENT_TIME"), List.of())),
                List.of(new EvidenceRuleService.Example("negative", List.of("ITEM_IDENTITY"),
                        List.of("EVENT_TIME")))));

        var refs = new ArrayList<EvidenceTrialSource.Ref>();
        var collection = new MaterialCollectionService(materialStore, files, access, Clock.systemUTC());
        for (String label : List.of("a", "b", "c")) {
            var observed = material.observe(actor, "observe-" + label,
                    new MaterialProductService.ObservationInput("EVIDENCE-SOURCE", "RECORD-" + label,
                            "1", "record " + label, null));
            var clue = material.discover(actor, "clue-" + label, observed.resultId(), "EVIDENCE-DISCOVERY");
            byte[] bytes = Files.readAllBytes(Path.of("data/enforcement/evidence-v2-acceptance-v1",
                    "record-" + label + ".txt"));
            var receipt = material.registerMaterial(actor, "material-" + label,
                    new MaterialProductService.MaterialInput(clue.resultId(), bytes,
                            "PROJECT-CASE-001", Instant.now().plusSeconds(3600),
                            List.of("SAVE", "PREVIEW", "ANALYZE"), actor, "2026-09-28"));
            collection.attest(actor, "collect-" + label, receipt.resultId(), 1,
                    "RECORD-" + label, "original collected");
            collection.verify("named-evidence-verifier", "verify-" + label,
                    receipt.resultId(), 2, "digest checked");
            var stored = materialStore.material(receipt.resultId()).orElseThrow();
            refs.add(new EvidenceTrialSource.Ref(stored.id(), "3", stored.sha256()));
        }
        var json = new ObjectMapper();
        Path caseFile = temporary.resolve("case-snapshot.json");
        var caseInput = json.createObjectNode().put("caseRef", "PROJECT-CASE-001")
                .put("version", "1").put("caseType", "PROJECT_DOCUMENT_REVIEW")
                .put("organization", "ORG-ENF-PROJECT").put("eventRef", "PROJECT-EVENT-001");
        var members = caseInput.putArray("materials");
        refs.forEach(ref -> members.addObject().put("id", ref.id()).put("version", ref.version())
                .put("sha256", ref.sha256()));
        json.writeValue(caseFile.toFile(), caseInput);
        var verified = new VerifiedEvidenceSource(caseFile, json, materialStore, files, access,
                Clock.systemUTC());
        var organizationStore = new Dm8EvidenceOrganizationStore(db, tx, json, Clock.systemUTC());
        var organize = new EvidenceOrganizationService(ruleStore, organizationStore, verified,
                (a, o, type, id, version, action, purpose) -> {
                    if (!allowed.get()) throw new SecurityException("Current grant revoked");
                });
        var request = new EvidenceOrganizationService.Input("ORG-CASE-001", "RULE-ORG", 2,
                new EvidenceRuleTrial.Request("PROJECT-CASE-001", "1", refs));
        var first = organize.organize(actor, "organize-1", request);
        assertThat(organize.organize(actor, "organize-1", request)).isEqualTo(first);
        var result = organize.read(actor, first.id(), 1);
        assertThat(result.state()).isEqualTo("PENDING_REVIEW");
        assertThat(result.evidenceSet()).hasSize(3);
        assertThat(result.matters()).extracting(EvidenceOrganizationStore.Matter::coverage)
                .containsExactly("CONFLICT", "PRESENT");
        assertThat(result.links()).extracting(EvidenceOrganizationStore.Link::relation)
                .contains("SUPPORT_CANDIDATE", "CORROBORATE_CANDIDATE", "CONTRADICT_CANDIDATE",
                        "IRRELEVANT_CANDIDATE");
        assertThat(result.unrelated()).containsExactly(refs.get(2).id());
        assertThat(result.unprocessed()).isEmpty();
        assertThat(result.times()).allSatisfy(time -> {
            assertThat(time.collectionTime()).isNotBlank();
            assertThat(time.uploadTime()).isNotBlank();
        });
        var secondSource = new DriverManagerDataSource(source.getUrl(), source.getUsername(), source.getPassword());
        var secondStore = new Dm8EvidenceOrganizationStore(new JdbcTemplate(secondSource),
                new TransactionTemplate(new DataSourceTransactionManager(secondSource)), json, Clock.systemUTC());
        assertThat(secondStore.revision(first.id(), 1).orElseThrow()).isEqualTo(result);
        var correction = new EvidenceOrganizationService.Correction(1, "EVENT_TIME", refs.getFirst().id(),
                "line 4", "UNVERIFIED", "A and B times conflict; neither is authoritative");
        assertThatThrownBy(() -> organize.correct("named-corrector", "bad-locator", first.id(),
                new EvidenceOrganizationService.Correction(1, "EVENT_TIME", refs.getFirst().id(),
                        "line 999", "UNVERIFIED", "invalid location")))
                .isInstanceOf(IllegalArgumentException.class);
        var corrected = organize.correct("named-corrector", "correct-1", first.id(), correction);
        assertThat(corrected.revision()).isEqualTo(2);
        assertThat(organize.correct("named-corrector", "correct-1", first.id(), correction))
                .isEqualTo(corrected);
        var secondRevision = organize.read("named-corrector", first.id(), 2);
        assertThat(secondRevision.links()).hasSize(result.links().size() + 1);
        assertThat(secondRevision.links().getLast().originKind()).isEqualTo("HUMAN_CORRECTION");
        assertThat(organize.read(actor, first.id(), 1).links()).isEqualTo(result.links());
        assertThatThrownBy(() -> organize.correct("named-corrector", "stale-correction", first.id(),
                correction)).isInstanceOf(IllegalArgumentException.class);
        var review = new EvidenceOrganizationService.Review(2, "CONFIRMED", "project technical review");
        assertThatThrownBy(() -> organize.review("named-corrector", "self-review", first.id(), review))
                .isInstanceOf(SecurityException.class);
        var reviewed = organize.review("named-reviewer", "review-1", first.id(), review);
        assertThat(reviewed.revision()).isEqualTo(3);
        assertThat(organize.review("named-reviewer", "review-1", first.id(), review))
                .isEqualTo(reviewed);
        assertThat(organize.read("named-reviewer", first.id(), 3).state()).isEqualTo("CONFIRMED");
        assertThat(organize.read("named-reviewer", first.id(), 3).reviewedBy()).isEqualTo("named-reviewer");
        assertThat(secondStore.revision(first.id(), 3).orElseThrow().links())
                .isEqualTo(secondRevision.links());
        var exclusion = new EvidenceOrganizationService.Exclude(3, refs.get(1).id(),
                "B conflicts with A and is excluded from this revision");
        assertThatThrownBy(() -> organize.exclude("named-corrector", "exclude-missing", first.id(),
                new EvidenceOrganizationService.Exclude(3, "NOT-IN-SET", "invalid")))
                .isInstanceOf(IllegalArgumentException.class);
        var excluded = organize.exclude("named-corrector", "exclude-b", first.id(), exclusion);
        assertThat(excluded.revision()).isEqualTo(4);
        assertThat(organize.exclude("named-corrector", "exclude-b", first.id(), exclusion))
                .isEqualTo(excluded);
        var rerun = organize.read("named-corrector", first.id(), 4);
        assertThat(rerun.state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(rerun.evidenceSet()).doesNotContain(refs.get(1));
        assertThat(rerun.links()).noneMatch(link -> link.evidenceId().equals(refs.get(1).id()));
        assertThat(rerun.links()).anyMatch(link -> "HUMAN_CORRECTION".equals(link.originKind()));
        assertThat(rerun.matters()).extracting(EvidenceOrganizationStore.Matter::coverage)
                .containsExactly("PRESENT", "PRESENT");
        assertThat(rerun.exclusions()).containsExactly(new EvidenceOrganizationStore.Exclusion(
                refs.get(1).id(), exclusion.reason(), "named-corrector"));
        assertThat(secondStore.revision(first.id(), 4).orElseThrow()).isEqualTo(rerun);
        assertThat(organize.read("named-reviewer", first.id(), 3).state()).isEqualTo("CONFIRMED");
        Path originalB = temporary.resolve("files").resolve(
                materialStore.material(refs.get(1).id()).orElseThrow().fileKey() + ".blob");
        Path unavailableB = temporary.resolve("unavailable-b.blob");
        Files.move(originalB, unavailableB);
        try {
            assertThatThrownBy(() -> organize.read("named-reviewer", first.id(), 3))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(organize.read("named-corrector", first.id(), 4)).isEqualTo(rerun);
            assertThat(organize.exclude("named-corrector", "exclude-b", first.id(), exclusion))
                    .isEqualTo(excluded);
        } finally {
            Files.move(unavailableB, originalB);
        }
        assertThat(organize.read("named-reviewer", first.id(), 3).state()).isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> organize.exclude("named-corrector", "stale-exclude", first.id(), exclusion))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> organize.organize(actor, "wrong-digest", new EvidenceOrganizationService.Input(
                "ORG-CASE-002", "RULE-ORG", 2, new EvidenceRuleTrial.Request("PROJECT-CASE-001", "1",
                List.of(new EvidenceTrialSource.Ref(refs.getFirst().id(), "3", "0".repeat(64)))))))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> organize.organize(actor, "wrong-version", new EvidenceOrganizationService.Input(
                "ORG-CASE-002", "RULE-ORG", 2, new EvidenceRuleTrial.Request("PROJECT-CASE-001", "1",
                List.of(new EvidenceTrialSource.Ref(refs.getFirst().id(), "2", refs.getFirst().sha256()))))))
                .isInstanceOf(SecurityException.class);
        var onlyUnrelated = organize.organize(actor, "only-unrelated", new EvidenceOrganizationService.Input(
                "ORG-CASE-GAP", "RULE-ORG", 2, new EvidenceRuleTrial.Request("PROJECT-CASE-001", "1",
                List.of(refs.get(2)))));
        var gap = organize.read(actor, onlyUnrelated.id(), 1);
        assertThat(gap.matters()).extracting(EvidenceOrganizationStore.Matter::coverage)
                .containsExactly("MISSING", "MISSING");
        assertThat(gap.links()).allSatisfy(link ->
                assertThat(link.relation()).isEqualTo("IRRELEVANT_CANDIDATE"));
        assertThat(gap.state()).isEqualTo("PENDING_REVIEW");
        ruleService.disable(actor, "rule-disable", "RULE-ORG", 2);
        assertThatThrownBy(() -> organize.organize(actor, "disabled-rule", new EvidenceOrganizationService.Input(
                "ORG-CASE-DISABLED", "RULE-ORG", 2, new EvidenceRuleTrial.Request(
                "PROJECT-CASE-001", "1", refs)))).isInstanceOf(SecurityException.class);
        assertThat(organize.read(actor, first.id(), 1)).isEqualTo(result);
        caseInput.put("organization", "OTHER-ORG");
        json.writeValue(caseFile.toFile(), caseInput);
        assertThatThrownBy(() -> organize.organize(actor, "cross-organization", request))
                .isInstanceOf(SecurityException.class);
        caseInput.put("organization", "ORG-ENF-PROJECT");
        json.writeValue(caseFile.toFile(), caseInput);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization", Integer.class)).isEqualTo(5);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_receipt", Integer.class)).isEqualTo(5);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_event", Integer.class)).isEqualTo(5);
        EvidenceOrganizationRecoveryCapture.pause(source, db, temporary, refs);
        allowed.set(false);
        assertThatThrownBy(() -> organize.organize(actor, "organize-1", request))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> organize.correct("named-corrector", "correct-1", first.id(), correction))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> organize.exclude("named-corrector", "exclude-b", first.id(), exclusion))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> organize.read(actor, first.id(), 1)).isInstanceOf(SecurityException.class);
    }
}
