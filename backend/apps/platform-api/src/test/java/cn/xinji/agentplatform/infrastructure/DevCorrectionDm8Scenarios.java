package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.taskcore.DevCorrectionStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.ServiceRole;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevCorrectionStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTaskCoreStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class DevCorrectionDm8Scenarios {
    private DevCorrectionDm8Scenarios() { }

    static void acrossConnections(DriverManagerDataSource source, Actor human, Actor job,
            Guard base, ZoneId zone) throws Exception {
        var first = new Dm8DevTaskCoreStore(source, new DataSourceTransactionManager(source), zone);
        var second = new Dm8DevTaskCoreStore(source, new DataSourceTransactionManager(source), zone);
        var writer = new Dm8DevCorrectionStore(source, new DataSourceTransactionManager(source), zone);
        var replay = new Dm8DevCorrectionStore(source, new DataSourceTransactionManager(source), zone);
        var sql = new JdbcTemplate(source);
        var input = new ObjectMapper().readTree(WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                "dev-correction-input-v1", "input.json")).toFile()).path("correction");
        String old = input.path("oldText").asText(), replacement = input.path("newText").asText();
        String utterance = input.path("userMessage").asText();
        byte[] manifest = Files.readAllBytes(WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                "dev-correction-input-v1", "manifest.json")));
        DevCorrectionStore.Source signed = new DevCorrectionStore.Source("DEV-CON-CORRECTION-INPUT-01",
                sha(manifest), sha(old.getBytes(StandardCharsets.UTF_8)),
                sha(replacement.getBytes(StandardCharsets.UTF_8)), sha(utterance.getBytes(StandardCharsets.UTF_8)),
                old, replacement, utterance);
        AtomicBoolean current = new AtomicBoolean(true);
        Guard guard = new Guard() {
            @Override public boolean humanCurrent(Actor actor, Instant now) { return base.humanCurrent(actor, now); }
            @Override public boolean inputAllowed(Actor actor, String kind, String text, Instant now) {
                return base.inputAllowed(actor, kind, text, now);
            }
            @Override public boolean inputDisplayAllowed(Actor actor, String kind, String text, Instant now) {
                return base.inputDisplayAllowed(actor, kind, text, now);
            }
            @Override public boolean correctionAllowed(Actor actor, String field, String text, Instant now) {
                return current.get() && humanCurrent(actor, now) && ("NEW_TEXT".equals(field)
                        && replacement.equals(text) || "UTTERANCE".equals(field) && utterance.equals(text)
                        || "MANIFEST_SHA".equals(field) && signed.manifestSha().equals(text)
                        || "OLD_SHA".equals(field) && signed.oldSha().equals(text));
            }
            @Override public boolean delegationCurrent(String owner, String organization, long version,
                    Instant until, Instant now) {
                return base.delegationCurrent(owner, organization, version, until, now);
            }
            @Override public boolean serviceCurrent(Actor actor, ServiceRole role, Instant now) {
                return base.serviceCurrent(actor, role, now);
            }
        };
        String task = first.create(human, "correction-dm8-create", "查找并比较用于温度调节的传感器、控制器和阀门方案", guard).taskId();
        first.append(human, task, "correction-dm8-append", 1, old, guard);
        var accepted = writer.correct(human, task, "correction-dm8-save", 2, 2, replacement, utterance,
                signed, guard);
        assertThat(replay.correct(human, task, "correction-dm8-save", 2, 2, replacement, utterance,
                signed, guard).receiptId()).isEqualTo(accepted.receiptId());
        assertThat(second.read(human, task, guard).corrections()).hasSize(1);
        assertThat(second.read(human, task, guard).messages().get(2).body()).isEqualTo(replacement);
        sql.update("UPDATE con2_message SET body_sha=? WHERE task_id=? AND revision=3", "0".repeat(64), task);
        assertThatThrownBy(() -> second.read(human, task, guard)).isInstanceOf(DevTaskCoreFailure.class)
                .hasMessage("SOURCE_UNAVAILABLE");
        sql.update("UPDATE con2_message SET body_sha=? WHERE task_id=? AND revision=3",
                digest(replacement), task);
        sql.update("UPDATE con2_correction SET manifest_sha=? WHERE task_id=?", "0".repeat(64), task);
        assertThatThrownBy(() -> second.read(human, task, guard)).isInstanceOf(DevTaskCoreFailure.class)
                .hasMessage("SOURCE_UNAVAILABLE");
        sql.update("UPDATE con2_correction SET manifest_sha=? WHERE task_id=?", signed.manifestSha(), task);
        assertThatThrownBy(() -> replay.correct(human, task, "correction-dm8-race", 2, 2,
                replacement, utterance, signed, guard)).isInstanceOf(DevTaskCoreFailure.class)
                .hasMessage("VERSION_CONFLICT");
        current.set(false);
        assertThatThrownBy(() -> second.read(human, task, guard)).isInstanceOf(DevTaskCoreFailure.class)
                .hasMessage("SOURCE_UNAVAILABLE");
        assertThatThrownBy(() -> replay.correct(human, task, "correction-dm8-save", 2, 2,
                replacement, utterance, signed, guard)).isInstanceOf(DevTaskCoreFailure.class)
                .hasMessage("SOURCE_UNAVAILABLE");
        current.set(true);
        sql.update("DELETE FROM con2_correction WHERE task_id=?", task);
        assertThatThrownBy(() -> second.read(human, task, guard)).isInstanceOf(DevTaskCoreFailure.class)
                .hasMessage("SOURCE_UNAVAILABLE");
        sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), task);
        assertThat(first.reconcile(job, guard).removed()).isGreaterThanOrEqualTo(1);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_correction WHERE task_id=?",
                Integer.class, task)).isZero();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String digest(String text) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            hash.update(new byte[] {(byte)(bytes.length >>> 24), (byte)(bytes.length >>> 16),
                    (byte)(bytes.length >>> 8), (byte)bytes.length});
            hash.update(bytes);
            return HexFormat.of().formatHex(hash.digest());
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
