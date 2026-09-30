package cn.xinji.agentplatform.enforcement.conduct;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceOrganizationStore;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceOrganizationService;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceRuleService;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceRuleStore;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceRuleTrial;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceTrialSource;
import cn.xinji.agentplatform.enforcement.evidence.VerifiedEvidenceSource;
import cn.xinji.agentplatform.enforcement.material.MaterialAccess;
import cn.xinji.agentplatform.enforcement.material.MaterialCollectionService;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialProductService;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class ConductHttpsEvidenceFixture {
    private final JdbcTemplate db;
    private final PlatformTransactionManager manager;
    private final ObjectMapper json;
    private final MaterialRepository materials;
    private final MaterialFiles files;
    private final EvidenceRuleStore evidenceRules;
    private final Path root;
    private final String organization;

    ConductHttpsEvidenceFixture(JdbcTemplate db, PlatformTransactionManager manager, ObjectMapper json,
            MaterialRepository materials, MaterialFiles files, EvidenceRuleStore evidenceRules,
            Path root, String organization) {
        this.db = db;
        this.manager = manager;
        this.json = json;
        this.materials = materials;
        this.files = files;
        this.evidenceRules = evidenceRules;
        this.root = root;
        this.organization = organization;
    }

    List<EvidenceTrialSource.Ref> prepare(String actor) throws Exception {
        MaterialAccess setup = new MaterialAccess() {
            @Override public void require(String a, String org, String type, String id,
                                          long version, String action, String purpose) { }
            @Override public void requireSource(String id, String use, long epoch) { }
            @Override public void prepareSource(String id, long epoch) { }
            @Override public void activateSource(String id, long epoch) { }
        };
        var material = new MaterialProductService(materials, files, setup, Clock.systemUTC());
        var collection = new MaterialCollectionService(materials, files, setup, Clock.systemUTC());
        material.registerSource(actor, "source", new MaterialProductService.SourceInput(
                "CONDUCT-HTTP-SOURCE", organization, "PROJECT-CASE-001", 1,
                Instant.now().plusSeconds(3600), List.of("QUERY", "SAVE", "DISPLAY", "ANALYZE"),
                "project-owned-evidence"));
        var positive = material.observe(actor, "positive", new MaterialProductService.ObservationInput(
                "CONDUCT-HTTP-SOURCE", "POS", "1", "record positive", null));
        var negative = material.observe(actor, "negative", new MaterialProductService.ObservationInput(
                "CONDUCT-HTTP-SOURCE", "NEG", "1", "missing", null));
        material.draftRule(actor, "discovery-draft", new MaterialProductService.RuleInput(
                "CONDUCT-HTTP-DISCOVERY", "CONDUCT-HTTP-SOURCE", "record", "project-method"));
        material.ruleState(actor, "discovery-publish", "CONDUCT-HTTP-DISCOVERY", 1,
                "PUBLISH_RULE", positive.resultId(), negative.resultId());
        var refs = new ArrayList<EvidenceTrialSource.Ref>();
        for (String label : List.of("a", "b", "c")) {
            var observed = material.observe(actor, "observe-" + label,
                    new MaterialProductService.ObservationInput("CONDUCT-HTTP-SOURCE",
                            "RECORD-" + label, "1", "record " + label, null));
            var clue = material.discover(actor, "clue-" + label, observed.resultId(),
                    "CONDUCT-HTTP-DISCOVERY");
            byte[] original = Files.readAllBytes(Path.of("data/enforcement/evidence-v2-acceptance-v1",
                    "record-" + label + ".txt"));
            var saved = material.registerMaterial(actor, "material-" + label,
                    new MaterialProductService.MaterialInput(clue.resultId(), original,
                            "PROJECT-CASE-001", Instant.now().plusSeconds(3600),
                            List.of("SAVE", "PREVIEW", "ANALYZE"), actor, "2026-09-28"));
            collection.attest(actor, "collect-" + label, saved.resultId(), 1,
                    "RECORD-" + label, "Original collected");
            collection.verify("named-independent-verifier", "verify-" + label, saved.resultId(), 2,
                    "SHA checked");
            var record = materials.material(saved.resultId()).orElseThrow();
            refs.add(new EvidenceTrialSource.Ref(record.id(), "3", record.sha256()));
        }
        var caseInput = json.createObjectNode().put("caseRef", "PROJECT-CASE-001")
                .put("version", "1").put("caseType", "PROJECT_DOCUMENT_REVIEW")
                .put("organization", organization).put("eventRef", "PROJECT-EVENT-001");
        var members = caseInput.putArray("materials");
        refs.forEach(ref -> members.addObject().put("id", ref.id()).put("version", ref.version())
                .put("sha256", ref.sha256()));
        json.writeValue(root.resolve("case.json").toFile(), caseInput);
        var evidenceRule = new EvidenceRuleService(evidenceRules,
                (a, o, id, version, action) -> { }, (type, ref, version, scope) -> { });
        evidenceRule.draft(actor, "evidence-draft", new EvidenceRuleService.DraftInput(
                "CONDUCT-HTTP-EVIDENCE-RULE", organization, "PROJECT_DOCUMENT_REVIEW", "docs/57#6.24",
                List.of(new EvidenceRuleStore.Matter("ITEM_IDENTITY", true,
                        "line-prefix:The submitted item is labeled "),
                        new EvidenceRuleStore.Matter("EVENT_TIME", true,
                                "line-prefix:The observed event time is "))));
        evidenceRule.publish(actor, "evidence-publish", "CONDUCT-HTTP-EVIDENCE-RULE",
                new EvidenceRuleService.Publication("1", "PROJECT-CASE-001", "REVIEW-CONDUCT-HTTP",
                        List.of(new EvidenceRuleService.Example("positive",
                                List.of("ITEM_IDENTITY", "EVENT_TIME"), List.of())),
                        List.of(new EvidenceRuleService.Example("negative",
                                List.of("ITEM_IDENTITY"), List.of("EVENT_TIME")))));
        var organizations = new EvidenceOrganizationService(evidenceRules,
                new Dm8EvidenceOrganizationStore(db, new TransactionTemplate(manager), json,
                        Clock.systemUTC()),
                new VerifiedEvidenceSource(root.resolve("case.json"), json, materials, files,
                        setup, Clock.systemUTC()),
                (a, o, type, id, version, action, purpose) -> { });
        organizations.organize(actor, "organize", new EvidenceOrganizationService.Input(
                "CONDUCT-HTTP-EVIDENCE", "CONDUCT-HTTP-EVIDENCE-RULE", 2,
                new EvidenceRuleTrial.Request("PROJECT-CASE-001", "1", refs)));
        return refs;
    }
}
