package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.taskcore.DevFeatureConfirmationStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevFeatureConfirmationStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevComparisonInputPreviewStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTextResultStore;
import cn.xinji.agentplatform.consultation.taskcore.DevComparisonInputPreviewStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTextResultStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTaskCoreStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class DevFeatureConfirmationDm8Scenarios {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
    private static final String MESSAGE = "补充：该方案使用温度传感器、自动控制器和阀门。";

    private DevFeatureConfirmationDm8Scenarios() { }

    static void acrossConnections(DriverManagerDataSource source, Actor human, Actor job,
                                  Guard guard, ZoneId zone) throws Exception {
        JdbcTemplate sql = new JdbcTemplate(source);
        var first = new Dm8DevFeatureConfirmationStore(source, new DataSourceTransactionManager(source), zone);
        var restarted = new Dm8DevFeatureConfirmationStore(source, new DataSourceTransactionManager(source), zone);
        var tasks = new Dm8DevTaskCoreStore(source, new DataSourceTransactionManager(source), zone);
        Path root = WorkspaceTestPaths.root().resolve(Path.of("data", "consultation", "dev-feature-candidates-v1"));
        ObjectMapper mapper = new ObjectMapper();
        var manifest = mapper.readTree(root.resolve("manifest.json").toFile());
        var payload = mapper.readTree(root.resolve("candidates.json").toFile());
        String manifestDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(root.resolve("manifest.json"))));
        List<DevFeatureConfirmationStore.Candidate> members = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            var member = payload.path("candidates").get(index);
            members.add(new DevFeatureConfirmationStore.Candidate(member.path("featureId").asText(),
                    member.path("exactText").asText(), manifest.path("candidateDigests").get(index)
                    .path("sha256").asText(), member.path("byteStart").asInt(), member.path("byteEnd").asInt()));
        }
        var allowed = new AtomicBoolean(true);
        var comparisonAllowed = new AtomicBoolean(true);
        var taskIdHolder = new AtomicReference<String>();
        Path comparisonRoot = WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                "dev-comparison-input-v1"));
        var comparisonManifest = mapper.readTree(comparisonRoot.resolve("manifest.json").toFile());
        var comparisonPayload = mapper.readTree(comparisonRoot.resolve("input.json").toFile());
        var comparisonDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(comparisonRoot.resolve("manifest.json"))));
        String comparisonInputDigest = rawSha(MESSAGE);
        List<DevFeatureConfirmationStore.Candidate> comparisonMembers = new ArrayList<>();
        for (var member : comparisonPayload.path("featureCandidates")) {
            comparisonMembers.add(new DevFeatureConfirmationStore.Candidate(member.path("featureId").asText(),
                    member.path("exactText").asText(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(member))), member.path("byteStart").asInt(),
                    member.path("byteEnd").asInt()));
        }
        DevFeatureConfirmationStore.SourceGuard candidates = (actor, manifestId, display, now) -> {
            if (!actor.principal().equals(human.principal())) return null;
            if (allowed.get() && "DEV-CON-FEATURE-CANDIDATES-01".equals(manifestId)) {
                return new DevFeatureConfirmationStore.Source("DEV-CON-FEATURE-CANDIDATES-01",
                        manifestDigest, payload.path("source").path("inputSha256").asText(),
                        sql.queryForObject("SELECT body_sha FROM con2_message WHERE task_id=? AND revision=2",
                                String.class, taskIdHolder.get()), 0, List.copyOf(members));
            }
            if (comparisonAllowed.get() && "DEV-CON-COMPARE-INPUT-01".equals(manifestId)) {
                return new DevFeatureConfirmationStore.Source(manifestId, comparisonDigest,
                        comparisonInputDigest, sql.queryForObject("SELECT body_sha FROM con2_message "
                                + "WHERE task_id=? AND revision=2", String.class, taskIdHolder.get()),
                        comparisonManifest.path("withdrawalVersion").asLong(), List.copyOf(comparisonMembers));
            }
            return null;
        };
        String task = tasks.create(human, "feature-dm8-create-123", GOAL, guard).taskId();
        taskIdHolder.set(task);
        tasks.append(human, task, "feature-dm8-append-123", 1, MESSAGE, guard);
        List<String> ids = List.of("F1", "F2", "F3");
        var confirmed = first.confirm(human, task, "feature-dm8-confirm-123", 2, 0,
                "DEV-CON-FEATURE-CANDIDATES-01", ids, guard, candidates);
        assertThat(confirmed.featureRevision()).isEqualTo(1);
        assertThat(restarted.confirm(human, task, "feature-dm8-confirm-123", 2, 0,
                "DEV-CON-FEATURE-CANDIDATES-01", ids, guard, candidates).receiptId())
                .isEqualTo(confirmed.receiptId());
        assertThat(restarted.read(human, task, 1, guard, candidates).candidates()).hasSize(3);
        var newConfirmation = restarted.confirm(human, task, "comparison-dm8-confirm-123", 2, 1,
                "DEV-CON-COMPARE-INPUT-01", ids, guard, candidates);
        assertThat(newConfirmation.featureRevision()).isEqualTo(2);
        assertThat(first.read(human, task, 2, guard, candidates).receiptId())
                .isEqualTo(newConfirmation.receiptId());
        var search = new Dm8DevTextResultStore(source, new DataSourceTransactionManager(source), zone);
        var patentManifest = mapper.readTree(WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                "dev-synthetic-v1", "manifest.json")).toFile());
        var patents = patentManifest.path("datasets").get(0).path("records");
        AtomicBoolean patentAllowed = new AtomicBoolean(true);
        DevTextResultStore.SourceGuard patentSources = (actor, id, version, digest, query, save, display, now) -> {
            if (!patentAllowed.get()) return null;
            for (var record : patents) {
                if (id.equals(record.path("recordId").asText())
                        && version.equals(record.path("sourceVersion").asText())
                        && (digest == null || digest.equals(record.path("contentSha256").asText()))) {
                    return new DevTextResultStore.Source(id, version, record.path("contentSha256").asText(),
                            record.path("locator").asText(), record.path("claimText").asText());
                }
            }
            return null;
        };
        var set = search.search(human, task, "preview-dm8-search-123", 2, 0, guard, patentSources);
        var focus = search.focus(human, task, "preview-dm8-focus-123", set.setId(), 2, 2, 0,
                guard, patentSources);
        var preview = new Dm8DevComparisonInputPreviewStore(source, new DataSourceTransactionManager(source), zone);
        DevComparisonInputPreviewStore.Sources previewSources = new DevComparisonInputPreviewStore.Sources() {
            @Override public DevFeatureConfirmationStore.Source features(Actor actor, Instant now) {
                return candidates.permitted(actor, "DEV-CON-COMPARE-INPUT-01", true, now);
            }
            @Override public DevTextResultStore.Source claim(Actor actor, String id, String version,
                                                              String digest, Instant now, boolean comparisonUse) {
                return comparisonUse && !comparisonAllowed.get() ? null
                        : patentSources.permitted(actor, id, version, digest, false, false, true, now);
            }
        };
        assertThat(preview.read(human, task, 2, focus.sequence(), guard, previewSources).recordId()).isEqualTo("P2");
        assertThat(new Dm8DevComparisonInputPreviewStore(source, new DataSourceTransactionManager(source), zone)
                .read(human, task, 2, focus.sequence(), guard, previewSources).claimText())
                .isEqualTo("传感器、手动阀门");
        int receipts = sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?",
                Integer.class, task);
        int events = sql.queryForObject("SELECT COUNT(*) FROM con2_event WHERE task_id=?",
                Integer.class, task);
        AtomicInteger checks = new AtomicInteger();
        DevComparisonInputPreviewStore.Sources withdrawnBeforeReturn = new DevComparisonInputPreviewStore.Sources() {
            @Override public DevFeatureConfirmationStore.Source features(Actor actor, Instant now) {
                if (checks.incrementAndGet() == 2) comparisonAllowed.set(false);
                return previewSources.features(actor, now);
            }
            @Override public DevTextResultStore.Source claim(Actor actor, String id, String version,
                                                              String digest, Instant now, boolean comparisonUse) {
                return previewSources.claim(actor, id, version, digest, now, comparisonUse);
            }
        };
        assertThatThrownBy(() -> preview.read(human, task, 2, focus.sequence(), guard, withdrawnBeforeReturn))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("SOURCE_UNAVAILABLE");
        assertThat(checks.get()).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task))
                .isEqualTo(receipts);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_event WHERE task_id=?", Integer.class, task))
                .isEqualTo(events);
        comparisonAllowed.set(true);
        assertThatThrownBy(() -> preview.read(human, task, 1, focus.sequence(), guard, previewSources))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("INPUT_NOT_BOUND");
        patentAllowed.set(false);
        assertThatThrownBy(() -> preview.read(human, task, 2, focus.sequence(), guard, previewSources))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("SOURCE_UNAVAILABLE");
        patentAllowed.set(true);
        allowed.set(false);
        assertThatThrownBy(() -> first.read(human, task, 1, guard, candidates))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("FEATURE_SOURCE_UNAVAILABLE");
        assertThat(restarted.read(human, task, 2, guard, candidates).featureRevision()).isEqualTo(2);
        allowed.set(true);
        comparisonAllowed.set(false);
        assertThatThrownBy(() -> first.read(human, task, 2, guard, candidates))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("FEATURE_SOURCE_UNAVAILABLE");
        assertThatThrownBy(() -> preview.read(human, task, 2, focus.sequence(), guard, previewSources))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("SOURCE_UNAVAILABLE");
        assertThat(restarted.read(human, task, 1, guard, candidates).featureRevision()).isEqualTo(1);
        comparisonAllowed.set(true);
        assertThat(preview.read(human, task, 2, focus.sequence(), guard, previewSources).recordId()).isEqualTo("P2");
        tasks.cancel(human, task, "preview-dm8-cancel-123", 2, guard);
        assertThatThrownBy(() -> preview.read(human, task, 2, focus.sequence(), guard, previewSources))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_CLOSED");
        sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), task);
        assertThatThrownBy(() -> restarted.read(human, task, 1, guard, candidates))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_EXPIRED");
        assertThat(tasks.reconcile(job, guard).removed()).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_feature_member WHERE task_id=?",
                Integer.class, task)).isZero();
    }

    private static String rawSha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
