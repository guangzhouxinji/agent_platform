package cn.xinji.agentplatform.enforcement.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceOrganizationStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceRuleStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8HandoffStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8MaterialRepository;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceOrganizationService;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceRuleService;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceRuleStore;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceRuleTrial;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceTrialSource;
import cn.xinji.agentplatform.enforcement.evidence.VerifiedEvidenceSource;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class HandoffProductTest {
    @TempDir Path root;

    @Test void verifiedEvidenceCanBeRequestedButOnlyNamedClaimCreatesAcceptance() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:enf5-" + System.nanoTime()
                + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = source.getConnection()) {
            for (String migration : List.of("V035__enforcement_v2_material.sql",
                    "V050__enforcement_v2_evidence_rules.sql",
                    "V051__enforcement_v2_evidence_publication.sql",
                    "V052__enforcement_v2_evidence_organizations.sql",
                    "V054__enforcement_v2_handoff.sql",
                    "V055__enforcement_v2_handoff_event_reason.sql"))
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("db/dm8", migration)));
        }
        exercise(source, root);
    }

    static void exercise(DriverManagerDataSource source, Path root) throws Exception {
        var db = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var materials = new Dm8MaterialRepository(db, tx, Clock.systemUTC());
        var files = new LocalMaterialFiles(root.resolve("originals"));
        var current = new AtomicBoolean(true);
        MaterialAccess materialAccess = new MaterialAccess() {
            @Override public void require(String actor, String org, String type, String id,
                    long version, String action, String purpose) {
                if (!current.get()) throw new SecurityException("Grant withdrawn");
            }
            @Override public void requireSource(String id, String use, long epoch) {
                if (!current.get()) throw new SecurityException("Source withdrawn");
            }
            @Override public void prepareSource(String id, long epoch) { }
            @Override public void activateSource(String id, long epoch) { }
        };
        var material = new MaterialProductService(materials, files, materialAccess, Clock.systemUTC());
        var collection = new MaterialCollectionService(materials, files, materialAccess, Clock.systemUTC());
        var actor = "named-package-creator";
        material.registerSource(actor, "source", new MaterialProductService.SourceInput(
                "HANDOFF-SOURCE", "ORG-ENF-PROJECT", "PROJECT-CASE-001", 1,
                Instant.now().plusSeconds(3600), List.of("QUERY", "SAVE", "DISPLAY", "ANALYZE"),
                "project-owned-evidence"));
        var positive = material.observe(actor, "positive", new MaterialProductService.ObservationInput(
                "HANDOFF-SOURCE", "POS", "1", "record positive", null));
        var negative = material.observe(actor, "negative", new MaterialProductService.ObservationInput(
                "HANDOFF-SOURCE", "NEG", "1", "missing", null));
        material.draftRule(actor, "discovery-draft", new MaterialProductService.RuleInput(
                "HANDOFF-DISCOVERY", "HANDOFF-SOURCE", "record", "project-method"));
        material.ruleState(actor, "discovery-publish", "HANDOFF-DISCOVERY", 1,
                "PUBLISH_RULE", positive.resultId(), negative.resultId());
        var refs = new ArrayList<EvidenceTrialSource.Ref>();
        var originals = new ArrayList<byte[]>();
        for (String label : List.of("a", "b", "c")) {
            var observed = material.observe(actor, "observe-" + label,
                    new MaterialProductService.ObservationInput("HANDOFF-SOURCE", "RECORD-" + label,
                            "1", "record " + label, null));
            var clue = material.discover(actor, "clue-" + label, observed.resultId(), "HANDOFF-DISCOVERY");
            byte[] bytes = Files.readAllBytes(Path.of("data/enforcement/evidence-v2-acceptance-v1",
                    "record-" + label + ".txt"));
            originals.add(bytes);
            var receipt = material.registerMaterial(actor, "material-" + label,
                    new MaterialProductService.MaterialInput(clue.resultId(), bytes,
                            "PROJECT-CASE-001", Instant.now().plusSeconds(3600),
                            List.of("SAVE", "PREVIEW", "ANALYZE"), actor, "2026-09-28"));
            collection.attest(actor, "collect-" + label, receipt.resultId(), 1,
                    "RECORD-" + label, "original collected");
            collection.verify("independent-verifier", "verify-" + label, receipt.resultId(), 2,
                    "original SHA checked");
            var saved = materials.material(receipt.resultId()).orElseThrow();
            refs.add(new EvidenceTrialSource.Ref(saved.id(), "3", saved.sha256()));
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
        rules.draft(actor, "rule-draft", new EvidenceRuleService.DraftInput("HANDOFF-RULE",
                "ORG-ENF-PROJECT", "PROJECT_DOCUMENT_REVIEW", "docs/57#6.24", List.of(
                new EvidenceRuleStore.Matter("ITEM_IDENTITY", true,
                        "line-prefix:The submitted item is labeled "),
                new EvidenceRuleStore.Matter("EVENT_TIME", true,
                        "line-prefix:The observed event time is "))));
        rules.publish(actor, "rule-publish", "HANDOFF-RULE", new EvidenceRuleService.Publication(
                "1", "PROJECT-CASE-001", "REVIEW-HANDOFF", List.of(new EvidenceRuleService.Example(
                "positive", List.of("ITEM_IDENTITY", "EVENT_TIME"), List.of())),
                List.of(new EvidenceRuleService.Example("negative", List.of("ITEM_IDENTITY"),
                        List.of("EVENT_TIME")))));
        var verified = new VerifiedEvidenceSource(caseFile, json, materials, files, materialAccess,
                Clock.systemUTC());
        var organizationStore = new Dm8EvidenceOrganizationStore(db, tx, json, Clock.systemUTC());
        var organization = new EvidenceOrganizationService(ruleStore, organizationStore, verified,
                (a, o, type, id, version, action, purpose) -> {
                    if (!current.get()) throw new SecurityException("Current grant withdrawn");
                });
        organization.organize(actor, "organize", new EvidenceOrganizationService.Input(
                "HANDOFF-ORG", "HANDOFF-RULE", 2,
                new EvidenceRuleTrial.Request("PROJECT-CASE-001", "1", refs)));
        var handoffStore = new Dm8HandoffStore(db, tx, json, Clock.systemUTC());
        var handoff = new HandoffService(handoffStore, organization::read,
                (a, o, type, id, version, action, purpose) -> {
                    if (!current.get()) throw new SecurityException("Current grant withdrawn");
                    if ("HUMAN_TASK".equals(type) && List.of("CLAIM", "SUBMIT").contains(action)
                            && !List.of("named-receiver", "another-receiver").contains(a))
                        throw new SecurityException("Human action requires named receiver");
                });
        var packageInput = new HandoffService.PackageInput("PACKAGE-001", "HANDOFF-ORG", 1,
                "REVIEW_EVIDENCE_GAPS");
        var created = handoff.create(actor, "package-key", packageInput);
        assertThat(handoff.create(actor, "package-key", packageInput)).isEqualTo(created);
        var pack = handoff.readPackage(actor, created.objectId(), 1);
        assertThat(pack.evidenceSetDigest()).hasSize(64);
        assertThat(pack.gaps()).contains("EVENT_TIME:CONFLICT");
        assertThat(pack.unprocessed()).isEmpty();
        var requestInput = new HandoffService.RequestInput("REQUEST-001", pack.id(), 1,
                pack.purposeRef());
        var requested = handoff.request(actor, "request-key", requestInput);
        assertThat(handoff.request(actor, "request-key", requestInput)).isEqualTo(requested);
        assertThat(handoff.readRequest(actor, requested.objectId()).state()).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_result_revision", Integer.class)).isZero();
        assertThatThrownBy(() -> handoff.claim(actor, "not-receiver", "REQUEST-001",
                new HandoffService.ClaimInput(1))).isInstanceOf(SecurityException.class);
        var claimed = handoff.claim("named-receiver", "claim-key", "REQUEST-001",
                new HandoffService.ClaimInput(1));
        assertThat(handoff.claim("named-receiver", "claim-key", "REQUEST-001",
                new HandoffService.ClaimInput(1))).isEqualTo(claimed);
        assertThat(handoff.readRequest("named-receiver", "REQUEST-001").state()).isEqualTo("ACCEPTED");
        assertThatThrownBy(() -> handoff.claim("another-receiver", "second-claim", "REQUEST-001",
                new HandoffService.ClaimInput(1))).isInstanceOf(IllegalArgumentException.class);
        var opinion = new HandoffService.ResultInput(2, "Needs human review of conflicting time",
                "A and B original times disagree");
        var submitted = handoff.submit("named-receiver", "result-key", "REQUEST-001", opinion);
        assertThat(handoff.submit("named-receiver", "result-key", "REQUEST-001", opinion))
                .isEqualTo(submitted);
        assertThat(handoff.readResult("named-receiver", "REQUEST-001").packageVersion()).isEqualTo(1);
        assertThat(handoff.readResult("named-receiver", "REQUEST-001").opinion())
                .contains("human review");
        var reopenedSource = new DriverManagerDataSource(source.getUrl(), source.getUsername(),
                source.getPassword());
        var reopenedDb = new JdbcTemplate(reopenedSource);
        var reopened = new Dm8HandoffStore(reopenedDb,
                new TransactionTemplate(new DataSourceTransactionManager(reopenedSource)), json,
                Clock.systemUTC());
        assertThat(reopened.findPackage(pack.id(), 1)).contains(pack);
        assertThat(reopened.findRequest("REQUEST-001").orElseThrow().state()).isEqualTo("RETURNED");
        assertThat(reopened.findResult("REQUEST-001")).contains(handoff.readResult(
                "named-receiver", "REQUEST-001"));
        assertThatThrownBy(() -> handoff.submit("named-receiver", "stale-result", "REQUEST-001", opinion))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_work_package_revision", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_request", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_result_revision", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_event", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf4_organization_receipt", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material", Integer.class)).isEqualTo(3);
        for (int i = 0; i < refs.size(); i++) {
            var saved = materials.material(refs.get(i).id()).orElseThrow();
            assertThat(files.read(saved.fileKey())).isEqualTo(originals.get(i));
        }
        var oldRequest = new HandoffService.RequestInput("REQUEST-OLD", pack.id(), 1,
                pack.purposeRef());
        handoff.request(actor, "old-request", oldRequest);
        organization.correct("named-corrector", "evidence-correction", "HANDOFF-ORG",
                new EvidenceOrganizationService.Correction(1, "EVENT_TIME", refs.getFirst().id(),
                        "line 4", "UNVERIFIED", "Conflicting time needs a person"));
        var revised = handoff.revise(actor, "package-revision", pack.id(),
                new HandoffService.RevisionInput(1, "HANDOFF-ORG", 2, "New human correction"));
        assertThat(revised.version()).isEqualTo(2);
        assertThat(handoff.readPackage(actor, pack.id(), 1)).isEqualTo(pack);
        assertThat(handoff.readPackage(actor, pack.id(), 2).workId()).isEqualTo(pack.workId());
        assertThat(handoff.readPackage(actor, pack.id(), 2).contentDigest())
                .isNotEqualTo(pack.contentDigest());
        var newRequest = new HandoffService.RequestInput("REQUEST-NEW", pack.id(), 2,
                pack.purposeRef());
        handoff.request(actor, "new-request", newRequest);
        handoff.claim("named-receiver", "old-claim", oldRequest.id(), new HandoffService.ClaimInput(1));
        handoff.submit("named-receiver", "old-result", oldRequest.id(),
                new HandoffService.ResultInput(2, "Opinion on prior package only", "V1 reference"));
        assertThat(handoff.readResult("named-receiver", oldRequest.id()).packageVersion()).isEqualTo(1);
        assertThat(handoff.readRequest(actor, newRequest.id()).state()).isEqualTo("PENDING");
        assertThat(handoffStore.findResult(newRequest.id())).isEmpty();
        assertThatThrownBy(() -> handoff.revise(actor, "stale-revision", pack.id(),
                new HandoffService.RevisionInput(1, "HANDOFF-ORG", 2, "Stale")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_work_package_revision", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_request", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_result_revision", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class)).isEqualTo(9);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_event", Integer.class)).isEqualTo(9);
        String winner;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var first = workers.submit(() -> compete(handoff, "named-receiver", "new-claim-a",
                    newRequest.id(), ready, start));
            var second = workers.submit(() -> compete(handoff, "another-receiver", "new-claim-b",
                    newRequest.id(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(value -> value.endsWith(":ACCEPTED")).hasSize(1);
            assertThat(outcomes).filteredOn(value -> value.endsWith(":CONFLICT")).hasSize(1);
            winner = outcomes.stream().filter(value -> value.endsWith(":ACCEPTED"))
                    .findFirst().orElseThrow().split(":")[0];
        }
        assertThat(handoff.readRequest(winner, newRequest.id()).claimant()).isEqualTo(winner);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class)).isEqualTo(10);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_event", Integer.class)).isEqualTo(10);
        HandoffStore interruptedStore = new HandoffStore() {
            @Override public java.util.Optional<Package> findPackage(String id, long version) {
                return handoffStore.findPackage(id, version);
            }
            @Override public long latestPackage(String id) { return handoffStore.latestPackage(id); }
            @Override public java.util.Optional<Request> findRequest(String id) {
                return handoffStore.findRequest(id);
            }
            @Override public List<Request> findRequests() { return handoffStore.findRequests(); }
            @Override public java.util.Optional<Result> findResult(String id) {
                return handoffStore.findResult(id);
            }
            @Override public Receipt command(String key, String fingerprint, String actor, String operation,
                    java.util.function.Supplier<Receipt> body) {
                return handoffStore.command(key, fingerprint, actor, operation, body);
            }
            @Override public Receipt command(String key, String fingerprint, String actor, String operation,
                    String reason, java.util.function.Supplier<Receipt> body) {
                return handoffStore.command(key, fingerprint, actor, operation, reason, body);
            }
            @Override public void addPackage(Package value) { handoffStore.addPackage(value); }
            @Override public void addRequest(Request value) { handoffStore.addRequest(value); }
            @Override public void updateRequest(Request previous, Request next) {
                throw new IllegalStateException("Simulated interrupted result commit");
            }
            @Override public void addResult(Result value) { handoffStore.addResult(value); }
        };
        var interrupted = new HandoffService(interruptedStore, organization::read,
                (a, o, type, id, version, action, purpose) -> {
                    if (!current.get()) throw new SecurityException("Current grant withdrawn");
                    if ("HUMAN_TASK".equals(type) && List.of("CLAIM", "SUBMIT").contains(action)
                            && !List.of("named-receiver", "another-receiver").contains(a))
                        throw new SecurityException("Human action requires named receiver");
                });
        assertThatThrownBy(() -> interrupted.submit(winner, "interrupted-result", newRequest.id(),
                new HandoffService.ResultInput(2, "Interrupted opinion", "No half commit")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(handoff.readRequest(winner, newRequest.id()).state()).isEqualTo("ACCEPTED");
        assertThat(handoffStore.findResult(newRequest.id())).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class)).isEqualTo(10);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_event", Integer.class)).isEqualTo(10);
        assertThat(handoff.tasks("named-package-creator")).isEmpty();
        assertThat(handoff.tasks("another-receiver")).hasSize(3);
        assertThatThrownBy(() -> handoff.submit("named-package-creator", "agent-result", newRequest.id(),
                new HandoffService.ResultInput(2, "Improper", "Not a claimant")))
                .isInstanceOf(SecurityException.class);
        var loser = "named-receiver".equals(winner) ? "another-receiver" : "named-receiver";
        assertThatThrownBy(() -> handoff.returnTask(loser, "wrong-return", newRequest.id(),
                new HandoffService.ReturnInput(2, "Not the claimant")))
                .isInstanceOf(IllegalArgumentException.class);
        var returned = handoff.returnTask(winner, "return-with-reason", newRequest.id(),
                new HandoffService.ReturnInput(2, "Conflict needs another reviewer"));
        assertThat(returned.version()).isEqualTo(3);
        assertThat(handoff.readRequest(actor, newRequest.id()).state()).isEqualTo("PENDING");
        assertThat(handoff.readRequest(actor, newRequest.id()).claimant()).isNull();
        assertThat(db.queryForObject("SELECT reason FROM enf5_human_event WHERE request_key=?",
                String.class, "return-with-reason")).isEqualTo("Conflict needs another reviewer");
        assertThatThrownBy(() -> handoff.submit(winner, "late-result", newRequest.id(),
                new HandoffService.ResultInput(2, "Late", "Old claim")))
                .isInstanceOf(IllegalArgumentException.class);
        var withdrawn = handoff.withdraw(actor, "withdraw-with-reason", newRequest.id(),
                new HandoffService.WithdrawalInput(3, "Review request cancelled"));
        assertThat(withdrawn.version()).isEqualTo(4);
        assertThat(handoff.readRequest(actor, newRequest.id()).state()).isEqualTo("WITHDRAWN");
        assertThat(db.queryForObject("SELECT reason FROM enf5_human_event WHERE request_key=?",
                String.class, "withdraw-with-reason")).isEqualTo("Review request cancelled");
        assertThatThrownBy(() -> handoff.claim("another-receiver", "late-claim", newRequest.id(),
                new HandoffService.ClaimInput(3))).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_result_revision", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class)).isEqualTo(12);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_human_event", Integer.class)).isEqualTo(12);
        current.set(false);
        assertThatThrownBy(() -> handoff.request(actor, "request-key", requestInput))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> handoff.readResult("named-receiver", "REQUEST-001"))
                .isInstanceOf(SecurityException.class);
        String winnerKey = "named-receiver".equals(winner) ? "new-claim-a" : "new-claim-b";
        assertThatThrownBy(() -> handoff.claim(winner, winnerKey, newRequest.id(),
                new HandoffService.ClaimInput(1))).isInstanceOf(SecurityException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf5_handoff_receipt", Integer.class)).isEqualTo(12);
        System.out.println("ENF_HANDOFF_H01_H02_PRODUCT packages=2 requests=3 results=2 "
                + "concurrent=ONE_ACCEPTED returned=PENDING withdrawn=WITHDRAWN "
                + "receipts=12 events=12 evidence=2 originals=3 revoked=DENIED");
    }

    private static String compete(HandoffService handoff, String actor, String key, String id,
                                  CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start unavailable");
        try {
            handoff.claim(actor, key, id, new HandoffService.ClaimInput(1));
            return actor + ":ACCEPTED";
        } catch (IllegalArgumentException conflict) {
            return actor + ":CONFLICT";
        }
    }
}
