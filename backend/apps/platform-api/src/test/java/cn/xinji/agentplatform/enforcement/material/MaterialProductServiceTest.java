package cn.xinji.agentplatform.enforcement.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8MaterialRepository;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

class MaterialProductServiceTest {
    @TempDir Path temporary;

    @Test void v2RuleReceiptOrphanAndHumanRevisionUseOriginalDevelopmentBytes() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:enf3" + System.nanoTime()
                + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/dm8/V035__enforcement_v2_material.sql")));
        }
        var db = new JdbcTemplate(source);
        var repository = new Dm8MaterialRepository(db,
                new TransactionTemplate(new DataSourceTransactionManager(source)), Clock.systemUTC());
        var fileRoot = temporary.resolve("material-files");
        var files = new LocalMaterialFiles(fileRoot);
        var authority = new CurrentAccess();
        var service = new MaterialProductService(repository, files, authority, Clock.systemUTC());
        String actor = "named-enforcement-developer";
        String sourceId = "PROJECT-DEV-OBSERVATION";
        String ruleId = "ENF-RULE-V1-DEV-MARKING-01";
        var sourceInput = new MaterialProductService.SourceInput(sourceId, "ORG-ENF-DEV",
                "docs/02#40", 1, Instant.now().plus(1, ChronoUnit.DAYS),
                List.of("QUERY", "SAVE", "DISPLAY"), "m01-source");
        service.registerSource(actor, "source-key", sourceInput);
        var records = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(
                Path.of("data/enforcement/material-dev-product-contract-v1/observations.json"))).get("records");
        var first = records.get(0);
        var observation = new MaterialProductService.ObservationInput(sourceId, first.get("recordId").asText(),
                first.get("version").asText(), first.get("sourceText").asText(),
                first.get("materialMention").asText(), List.of(
                new MaterialWork.ActorInput("OPERATOR_CANDIDATE", "运营方甲", null),
                new MaterialWork.ActorInput("SUPPLIER_CANDIDATE", "供货方乙", null)));
        var observed = service.observe(actor, "observe-key", observation);
        var second = records.get(1);
        var negative = service.observe(actor, "negative-key", new MaterialProductService.ObservationInput(
                sourceId, second.get("recordId").asText(), second.get("version").asText(),
                second.get("sourceText").asText(), second.get("materialMention").asText()));
        assertThat(service.observe(actor, "observe-key", observation)).isEqualTo(observed);
        assertThatThrownBy(() -> service.observe(actor, "observe-key",
                new MaterialProductService.ObservationInput(sourceId, "OBS-001", "1", "changed", "M")))
                .isInstanceOf(IllegalArgumentException.class);
        var draft = service.draftRule(actor, "draft-key", new MaterialProductService.RuleInput(ruleId, sourceId,
                "专利产品", "docs/02#40"));
        assertThat(repository.rule(ruleId).orElseThrow().state()).isEqualTo("DRAFT");
        int receiptsBeforeTrial = db.queryForObject("SELECT COUNT(*) FROM enf3_receipt", Integer.class);
        int eventsBeforeTrial = db.queryForObject("SELECT COUNT(*) FROM enf3_event", Integer.class);
        assertThat(service.trialRule(actor, ruleId, observed.resultId()).outcome()).isEqualTo("MATCH");
        assertThat(service.trialRule(actor, ruleId, negative.resultId()).outcome()).isEqualTo("NO_MATCH");
        String otherSource = "PROJECT-DEV-OTHER-SOURCE";
        service.registerSource(actor, "other-source", new MaterialProductService.SourceInput(otherSource,
                "ORG-ENF-DEV", "docs/02#40", 1, Instant.now().plus(1, ChronoUnit.DAYS),
                List.of("QUERY", "SAVE", "DISPLAY"), "m01-source"));
        assertThatThrownBy(() -> service.observe(actor, "missing-text", new MaterialProductService.ObservationInput(
                otherSource, "OBS-MISSING", "1", "", null))).isInstanceOf(IllegalArgumentException.class);
        var cross = service.observe(actor, "cross-observation", new MaterialProductService.ObservationInput(
                otherSource, "OBS-CROSS", "1", first.get("sourceText").asText(), null));
        assertThat(service.trialRule(actor, ruleId, cross.resultId()).outcome()).isEqualTo("NOT_APPLICABLE");
        authority.available = false;
        assertThatThrownBy(() -> service.trialRule(actor, ruleId, observed.resultId()))
                .isInstanceOf(SecurityException.class);
        authority.available = true;
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='missing-text'",
                Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_clue", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE operation='DISCOVER'",
                Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt", Integer.class)).isEqualTo(receiptsBeforeTrial + 2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_event", Integer.class)).isEqualTo(eventsBeforeTrial + 2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_clue", Integer.class)).isZero();
        assertThatThrownBy(() -> service.discover(actor, "trial-key", observed.resultId(), ruleId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_clue", Integer.class)).isZero();
        assertThatThrownBy(() -> service.ruleState(actor, "publish-key", draft.resultId(), 1,
                "PUBLISH_RULE", observed.resultId(), observed.resultId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_rule_review", Integer.class)).isZero();
        var published = service.ruleState(actor, "publish-key", draft.resultId(), 1,
                "PUBLISH_RULE", observed.resultId(), negative.resultId());
        assertThat(repository.rule(ruleId).orElseThrow().state()).isEqualTo("ACTIVE");
        assertThat(repository.rule(ruleId).orElseThrow().publisher()).isEqualTo(actor);
        assertThat(service.ruleState(actor, "publish-key", ruleId, 1, "PUBLISH_RULE",
                observed.resultId(), negative.resultId())).isEqualTo(published);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_rule_review", Integer.class)).isEqualTo(1);
        var clue = service.discover(actor, "discovery-key", observed.resultId(), ruleId);
        assertThat(service.discover(actor, "discovery-key", observed.resultId(), ruleId)).isEqualTo(clue);
        service.draftRule(actor, "second-draft", new MaterialProductService.RuleInput(
                "ENF-DEV-SECOND-MARKING", sourceId, "专利产品", "docs/02#40"));
        service.ruleState(actor, "second-publish", "ENF-DEV-SECOND-MARKING", 1, "PUBLISH_RULE",
                observed.resultId(), negative.resultId());
        assertThat(service.discover(actor, "second-discovery", observed.resultId(),
                "ENF-DEV-SECOND-MARKING").resultId()).isEqualTo(clue.resultId());
        assertThat(service.readClueRules(actor, clue.resultId())).hasSize(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_clue", Integer.class)).isEqualTo(1);
        assertThat(service.readObservation(actor, observed.resultId()).text()).isEqualTo(observation.text());
        assertThat(service.readActors(actor, observed.resultId())).extracting(MaterialWork.ActorMention::role)
                .containsExactly("OPERATOR_CANDIDATE", "SUPPLIER_CANDIDATE");
        assertThat(service.readActors(actor, observed.resultId())).allSatisfy(candidate -> {
            assertThat(candidate.locator()).startsWith("text:");
            assertThat(candidate.businessCode()).isNull();
        });
        var revision = service.revise(actor, "revision-key", clue.resultId(), 1,
                observation.text() + " 待核", "原文保留并追加人工意见");
        assertThat(revision.resultVersion()).isEqualTo(2);
        assertThat(service.revisions(actor, clue.resultId()).getFirst().originalText()).isEqualTo(observation.text());
        assertThat(service.revisions(actor, clue.resultId()).getFirst().materialGap())
                .isEqualTo("MENTION_ONLY_NO_FILE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material", Integer.class)).isZero();
        service.ruleState(actor, "disable-key", ruleId, 1, "DISABLE_RULE", null, null);
        assertThat(repository.rule(ruleId).orElseThrow().state()).isEqualTo("DISABLED");
        assertThatThrownBy(() -> service.discover(actor, "new-discovery-key", observed.resultId(), ruleId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_clue", Integer.class)).isEqualTo(1);

        byte[] original = Files.readAllBytes(Path.of(
                "data/enforcement/material-dev-product-contract-v1/original-M-01.txt"));
        Instant fileExpiry = Instant.now().plus(1, ChronoUnit.DAYS);
        List<String> fileUses = List.of("SAVE", "PREVIEW", "DOWNLOAD");
        db.execute("ALTER TABLE enf3_material ADD CONSTRAINT ck_enf3_fault CHECK (collector <> 'FAIL_BIND')");
        assertThatThrownBy(() -> service.registerMaterial(actor, "binding-failure",
                new MaterialProductService.MaterialInput(clue.resultId(), original, "docs/02#40", fileExpiry,
                        fileUses, "FAIL_BIND", "2026-09-27")))
                .isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id='binding-failure'",
                Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material", Integer.class)).isZero();
        var material = service.registerMaterial(actor, "material-key",
                new MaterialProductService.MaterialInput(clue.resultId(), original, "docs/02#40", fileExpiry,
                        fileUses, actor, "2026-09-27"));
        assertThat(service.preview(actor, material.resultId())).isEqualTo(original);
        var collection = new MaterialCollectionService(repository, files, authority, Clock.systemUTC());
        String materialId = material.resultId();
        String verifier = "named-enforcement-verifier";
        int beforeActions = db.queryForObject("SELECT COUNT(*) FROM enf3_material_action", Integer.class);
        assertThatThrownBy(() -> collection.verify(verifier, "verify-without-chain", materialId, 1, "bytes checked"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> collection.attest(verifier, "wrong-collector", materialId, 1,
                "OBS-001/original-M-01", "received original"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material_action", Integer.class))
                .isEqualTo(beforeActions);
        Path originalPath = fileRoot.resolve(repository.material(materialId).orElseThrow().fileKey() + ".blob");
        Files.writeString(originalPath, "changed original bytes");
        assertThatThrownBy(() -> collection.attest(actor, "tampered-collect", materialId, 1,
                "OBS-001/original-M-01", "received original"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material_action", Integer.class))
                .isEqualTo(beforeActions);
        Files.write(originalPath, original);
        var attested = collection.attest(actor, "collect-1", materialId, 1,
                "OBS-001/original-M-01", "received original");
        assertThat(attested.resultVersion()).isEqualTo(2);
        assertThat(collection.attest(actor, "collect-1", materialId, 1,
                "OBS-001/original-M-01", "received original")).isEqualTo(attested);
        assertThatThrownBy(() -> collection.verify(actor, "self-verify", materialId, 2, "bytes checked"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> collection.verify(verifier, "stale-verify", materialId, 1, "bytes checked"))
                .isInstanceOf(IllegalArgumentException.class);
        authority.available = false;
        assertThatThrownBy(() -> collection.verify(verifier, "withdrawn-verify", materialId, 2,
                "bytes checked")).isInstanceOf(SecurityException.class);
        authority.available = true;
        assertThat(repository.material(materialId).orElseThrow().version()).isEqualTo(2);
        assertThat(collection.verify(verifier, "verify-1", materialId, 2, "bytes checked").resultVersion())
                .isEqualTo(3);
        assertThat(collection.actions(verifier, materialId)).extracting(MaterialWork.MaterialAction::action)
                .containsExactly("UPLOAD", "COLLECT_ATTEST", "VERIFY");
        assertThat(repository.material(materialId).orElseThrow().state()).isEqualTo("VERIFIED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_receipt WHERE request_id IN "
                + "('verify-without-chain','wrong-collector','self-verify','stale-verify',"
                + "'tampered-collect','withdrawn-verify')", Integer.class)).isZero();
        assertThat(service.registerMaterial(actor, "material-key",
                new MaterialProductService.MaterialInput(clue.resultId(), original, "docs/02#40", fileExpiry,
                        fileUses, actor, "2026-09-27")))
                .isEqualTo(material);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_material", Integer.class)).isEqualTo(1);
        for (var path : Files.list(fileRoot).toList())
            Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(11, ChronoUnit.MINUTES)));
        var failOnce = new AtomicBoolean(true);
        MaterialFiles flaky = new MaterialFiles() {
            @Override public Staged stage(byte[] bytes) { return files.stage(bytes); }
            @Override public byte[] read(String key) { return files.read(key); }
            @Override public List<String> stagedKeys() { return files.stagedKeys(); }
            @Override public void discard(String key) {
                if (failOnce.getAndSet(false)) throw new IllegalStateException("File volume temporarily unavailable");
                files.discard(key);
            }
        };
        var cleanup = new MaterialProductService(repository, flaky, authority, Clock.systemUTC());
        assertThatThrownBy(cleanup::cleanOrphans).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_cleanup_event WHERE state='FAILED'", Integer.class))
                .isEqualTo(1);
        assertThat(cleanup.cleanOrphans()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_cleanup_event WHERE state='SUCCEEDED'", Integer.class))
                .isEqualTo(2);
        assertThat(service.preview(actor, material.resultId())).isEqualTo(original);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf3_event", Integer.class)).isEqualTo(16);
        authority.available = false;
        assertThatThrownBy(() -> service.preview(actor, material.resultId()))
                .isInstanceOf(SecurityException.class);
    }

    private static final class CurrentAccess implements MaterialAccess {
        boolean available = true;
        @Override public void require(String actor, String organization, String resourceType, String resourceId,
                                      long version, String action, String purpose) {
            if (!available || !("named-enforcement-developer".equals(actor)
                    || "named-enforcement-verifier".equals(actor))
                    || !"ORG-ENF-DEV".equals(organization)) throw new SecurityException("Current grant refused");
        }
        @Override public void requireSource(String sourceId, String use, long epoch) {
            if (!available || epoch != 1) throw new SecurityException("Current source refused");
        }
        @Override public void prepareSource(String sourceId, long epoch) { requireSource(sourceId, "SAVE", epoch); }
        @Override public void activateSource(String sourceId, long epoch) { requireSource(sourceId, "SAVE", epoch); }
    }
}
