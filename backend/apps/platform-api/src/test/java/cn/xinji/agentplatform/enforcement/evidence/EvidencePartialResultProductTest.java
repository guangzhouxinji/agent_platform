package cn.xinji.agentplatform.enforcement.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceRuleStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceOrganizationStore;
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

class EvidencePartialResultProductTest {
    @TempDir Path root;

    @Test void verifiedOriginalsYieldExplicitPartialButCriticalFailureClosesWithoutWrites() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:enf4-partial-" + System.nanoTime()
                + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = dataSource.getConnection()) {
            for (String migration : List.of("V035__enforcement_v2_material.sql",
                    "V050__enforcement_v2_evidence_rules.sql",
                    "V051__enforcement_v2_evidence_publication.sql",
                    "V052__enforcement_v2_evidence_organizations.sql"))
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8", migration)));
        }
        exercise(dataSource, root);
    }

    static void exercise(DriverManagerDataSource dataSource, Path root) throws Exception {
        var db = new JdbcTemplate(dataSource);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var materialStore = new Dm8MaterialRepository(db, tx, Clock.systemUTC());
        var files = new LocalMaterialFiles(root.resolve("originals"));
        var sourceAllowed = new AtomicBoolean(true);
        MaterialAccess access = new MaterialAccess() {
            @Override public void require(String actor, String org, String type, String id,
                                          long version, String action, String purpose) { }
            @Override public void requireSource(String id, String use, long epoch) {
                if (!sourceAllowed.get()) throw new SecurityException("Current source watermark withdrawn");
            }
            @Override public void prepareSource(String id, long epoch) { }
            @Override public void activateSource(String id, long epoch) { }
        };
        var product = new MaterialProductService(materialStore, files, access, Clock.systemUTC());
        var collect = new MaterialCollectionService(materialStore, files, access, Clock.systemUTC());
        String actor = "evidence-collector";
        product.registerSource(actor, "source", new MaterialProductService.SourceInput(
                "PARTIAL-SOURCE", "ORG-ENF-PROJECT", "PROJECT-CASE-001", 1,
                Instant.now().plusSeconds(3600), List.of("QUERY", "SAVE", "DISPLAY", "ANALYZE"),
                "approved-project-originals"));
        var positive = product.observe(actor, "positive", new MaterialProductService.ObservationInput(
                "PARTIAL-SOURCE", "POS", "1", "record positive", null));
        var negative = product.observe(actor, "negative", new MaterialProductService.ObservationInput(
                "PARTIAL-SOURCE", "NEG", "1", "missing", null));
        product.draftRule(actor, "discovery-draft", new MaterialProductService.RuleInput(
                "PARTIAL-DISCOVERY", "PARTIAL-SOURCE", "record", "project-method"));
        product.ruleState(actor, "discovery-publish", "PARTIAL-DISCOVERY", 1,
                "PUBLISH_RULE", positive.resultId(), negative.resultId());
        var refs = new ArrayList<EvidenceTrialSource.Ref>();
        byte[][] originals = {
                Files.readAllBytes(Path.of("data/enforcement/evidence-v2-acceptance-v1/record-a.txt")),
                new byte[]{(byte) 0xc3, (byte) 0x28, 0x0a},
                "Project document without an event reference\nThe submitted item is labeled X.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
        };
        for (int i = 0; i < originals.length; i++) {
            var observation = product.observe(actor, "observe-" + i,
                    new MaterialProductService.ObservationInput("PARTIAL-SOURCE", "RECORD-" + i,
                            "1", "record " + i, null));
            var clue = product.discover(actor, "clue-" + i, observation.resultId(), "PARTIAL-DISCOVERY");
            var receipt = product.registerMaterial(actor, "material-" + i,
                    new MaterialProductService.MaterialInput(clue.resultId(), originals[i],
                            "PROJECT-CASE-001", Instant.now().plusSeconds(3600),
                            List.of("SAVE", "PREVIEW", "ANALYZE"), actor, "2026-09-28"));
            collect.attest(actor, "collect-" + i, receipt.resultId(), 1,
                    "RECORD-" + i, "original collected");
            collect.verify("independent-verifier", "verify-" + i, receipt.resultId(), 2,
                    "bytes checked");
            var stored = materialStore.material(receipt.resultId()).orElseThrow();
            refs.add(new EvidenceTrialSource.Ref(stored.id(), "3", stored.sha256()));
        }
        var json = new ObjectMapper();
        var caseFile = root.resolve("case.json");
        var caseInput = json.createObjectNode().put("caseRef", "PROJECT-CASE-001")
                .put("version", "1").put("caseType", "PROJECT_DOCUMENT_REVIEW")
                .put("organization", "ORG-ENF-PROJECT").put("eventRef", "PROJECT-EVENT-001");
        var members = caseInput.putArray("materials");
        refs.forEach(ref -> members.addObject().put("id", ref.id()).put("version", ref.version())
                .put("sha256", ref.sha256()));
        json.writeValue(caseFile.toFile(), caseInput);
        var ruleStore = new Dm8EvidenceRuleStore(db, tx, Clock.systemUTC());
        var rules = new EvidenceRuleService(ruleStore, (a, o, id, version, action) -> { },
                (type, ref, version, scope) -> { });
        rules.draft(actor, "rule-draft", new EvidenceRuleService.DraftInput("PARTIAL-RULE",
                "ORG-ENF-PROJECT", "PROJECT_DOCUMENT_REVIEW", "docs/57#6.24", List.of(
                new EvidenceRuleStore.Matter("ITEM_IDENTITY", true,
                        "line-prefix:The submitted item is labeled "),
                new EvidenceRuleStore.Matter("EVENT_TIME", true,
                        "line-prefix:The observed event time is "))));
        rules.publish(actor, "rule-publish", "PARTIAL-RULE", new EvidenceRuleService.Publication(
                "1", "PROJECT-CASE-001", "REVIEW-PARTIAL", List.of(new EvidenceRuleService.Example(
                "positive", List.of("ITEM_IDENTITY", "EVENT_TIME"), List.of())),
                List.of(new EvidenceRuleService.Example("negative", List.of("ITEM_IDENTITY"),
                        List.of("EVENT_TIME")))));
        var verified = new VerifiedEvidenceSource(caseFile, json, materialStore, files, access, Clock.systemUTC());
        var trial = new EvidenceRuleTrial(ruleStore, verified,
                (a, o, type, id, version, action, purpose) -> { });
        int receipts = count(db, "enf4_evidence_receipt");
        int events = count(db, "enf4_evidence_event");
        int materials = count(db, "enf3_material");
        var request = new EvidenceRuleTrial.Request("PROJECT-CASE-001", "1", refs);
        var partial = trial.run(actor, "PARTIAL-RULE", 1, request);
        assertThat(partial.unprocessedMaterials()).containsExactly(
                refs.get(1).id() + ":UNPARSEABLE_UTF8",
                refs.get(2).id() + ":EVENT_REFERENCE_MISSING");
        assertThat(partial.matters()).extracting(EvidenceRuleTrial.MatterResult::coverage)
                .containsExactly("PRESENT", "PRESENT");
        var organizationStore = new Dm8EvidenceOrganizationStore(db, tx, json, Clock.systemUTC());
        var organization = new EvidenceOrganizationService(ruleStore, organizationStore, verified,
                (a, o, type, id, version, action, purpose) -> { });
        var organized = organization.organize(actor, "organize-partial", new EvidenceOrganizationService.Input(
                "ORG-PARTIAL", "PARTIAL-RULE", 2, request));
        var persisted = organization.read(actor, organized.id(), 1);
        assertThat(persisted.unprocessed()).containsExactlyElementsOf(partial.unprocessedMaterials());
        assertThat(persisted.links()).noneMatch(link -> link.evidenceId().equals(refs.get(1).id())
                || link.evidenceId().equals(refs.get(2).id()));
        assertThat(persisted.times()).filteredOn(time -> time.evidenceId().equals(refs.get(1).id()))
                .singleElement().satisfies(time -> {
                    assertThat(time.eventTime()).isNull();
                    assertThat(time.eventUnknown()).isEqualTo("UNPARSEABLE_ORIGINAL");
                });
        var reopenedSource = new DriverManagerDataSource(dataSource.getUrl(),
                dataSource.getUsername(), dataSource.getPassword());
        assertThat(new Dm8EvidenceOrganizationStore(new JdbcTemplate(reopenedSource),
                new TransactionTemplate(new DataSourceTransactionManager(reopenedSource)), json,
                Clock.systemUTC()).revision(organized.id(), 1).orElseThrow().unprocessed())
                .containsExactlyElementsOf(partial.unprocessedMaterials());
        int organizationReceipts = count(db, "enf4_organization_receipt");
        int organizationEvents = count(db, "enf4_organization_event");
        assertThatThrownBy(() -> trial.run(actor, "PARTIAL-RULE", 1,
                new EvidenceRuleTrial.Request("PROJECT-CASE-001", "1", refs.subList(1, 3))))
                .isInstanceOf(SecurityException.class);
        var missing = materialStore.material(refs.get(1).id()).orElseThrow();
        Path original = root.resolve("originals").resolve(missing.fileKey() + ".blob");
        Path moved = root.resolve("temporarily-unavailable.blob");
        Files.move(original, moved);
        try {
            assertThatThrownBy(() -> trial.run(actor, "PARTIAL-RULE", 1, request))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> organization.organize(actor, "organize-missing-file",
                    new EvidenceOrganizationService.Input("ORG-MISSING-FILE", "PARTIAL-RULE", 2, request)))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            Files.move(moved, original);
        }
        sourceAllowed.set(false);
        assertThatThrownBy(() -> trial.run(actor, "PARTIAL-RULE", 1, request))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("watermark withdrawn");
        assertThatThrownBy(() -> organization.organize(actor, "organize-withdrawn",
                new EvidenceOrganizationService.Input("ORG-WITHDRAWN", "PARTIAL-RULE", 2, request)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> organization.organize(actor, "organize-partial",
                new EvidenceOrganizationService.Input("ORG-PARTIAL", "PARTIAL-RULE", 2, request)))
                .isInstanceOf(SecurityException.class);
        sourceAllowed.set(true);
        assertThat(count(db, "enf4_evidence_receipt")).isEqualTo(receipts);
        assertThat(count(db, "enf4_evidence_event")).isEqualTo(events);
        assertThat(count(db, "enf4_organization")).isEqualTo(1);
        assertThat(count(db, "enf4_organization_receipt")).isEqualTo(organizationReceipts);
        assertThat(count(db, "enf4_organization_event")).isEqualTo(organizationEvents);
        assertThat(count(db, "enf3_material")).isEqualTo(materials);
        for (int i = 0; i < refs.size(); i++) {
            var saved = materialStore.material(refs.get(i).id()).orElseThrow();
            assertThat(files.read(saved.fileKey())).isEqualTo(originals[i]);
        }
        var reopened = new JdbcTemplate(new DriverManagerDataSource(dataSource.getUrl(),
                dataSource.getUsername(), dataSource.getPassword()));
        assertThat(count(reopened, "enf4_evidence_receipt")).isEqualTo(receipts);
        assertThat(count(reopened, "enf4_evidence_event")).isEqualTo(events);
        assertThat(count(reopened, "enf4_organization")).isEqualTo(1);
        assertThat(count(reopened, "enf4_organization_receipt")).isEqualTo(organizationReceipts);
        assertThat(count(reopened, "enf4_organization_event")).isEqualTo(organizationEvents);
        System.out.println("ENF_EVIDENCE_PARTIAL_PRODUCT parsed=1 unprocessed=2 all_unparseable=DENIED "
                + "missing_file=DENIED withdrawn_source=DENIED evidence_receipts=" + receipts + " evidence_events=" + events
                + " organizations=1 organization_receipts=" + organizationReceipts
                + " organization_events=" + organizationEvents + " material_rows=" + materials
                + " originals_unchanged=3");
    }

    private static int count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
