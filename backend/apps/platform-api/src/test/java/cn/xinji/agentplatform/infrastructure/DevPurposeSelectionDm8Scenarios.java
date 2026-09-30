package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.taskcore.DevComparisonInputPreviewStore.Preview;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevPurposeSelectionStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTaskCoreStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class DevPurposeSelectionDm8Scenarios {
    static void acrossConnections(DriverManagerDataSource source, Actor human, Actor job,
                                  Guard guard, ZoneId zone) throws Exception {
        var sql = new JdbcTemplate(source);
        var task = new Dm8DevTaskCoreStore(source, new DataSourceTransactionManager(source), zone);
        var first = new Dm8DevPurposeSelectionStore(source, new DataSourceTransactionManager(source), zone);
        var restarted = new Dm8DevPurposeSelectionStore(source, new DataSourceTransactionManager(source), zone);
        var created = task.create(human, "purpose-dm8-create-123", "查找并比较用于温度调节的传感器、控制器和阀门方案", guard);
        String id = created.taskId();
        String appendReceipt = task.append(human, id, "purpose-dm8-append-123", 1,
                "补充：该方案使用温度传感器、自动控制器和阀门。", guard).receiptId();
        String set = UUID.randomUUID().toString();
        String receipt = created.receiptId();
        Timestamp now = Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        sql.update("INSERT INTO con2_result_state(task_id,result_seq,focus_seq) VALUES (?,1,1)", id);
        sql.update("INSERT INTO con2_result_set(set_id,task_id,seq,input_revision,method_version,actual_scope,"
                        + "receipt_id,created_at) VALUES (?,?,1,2,'SYNTHETIC',1,?,?)", set, id, receipt, now);
        sql.update("INSERT INTO con2_result_member(set_id,ordinal,record_id,source_version,content_sha,locator) "
                        + "VALUES (?,1,'P1','v1',?,'claim:1')", set, "a".repeat(64));
        sql.update("INSERT INTO con2_focus(task_id,seq,set_id,ordinal,input_revision,receipt_id,created_at) "
                + "VALUES (?,1,?,1,2,?,?)", id, set, receipt, now);
        sql.update("INSERT INTO con2_feature_state(task_id,feature_seq) VALUES (?,1)", id);
        sql.update("INSERT INTO con2_feature_revision(task_id,feature_seq,input_revision,actor_ref,org_ref,"
                        + "auth_version,manifest_id,manifest_sha,input_sha,withdrawal_version,receipt_id,"
                        + "created_at) VALUES (?,1,2,?,?,1,'DEV-CON-COMPARE-INPUT-01',?,?,0,?,?)",
                id, human.principal(), human.organization(), "b".repeat(64), "c".repeat(64),
                appendReceipt, now);
        var check = (cn.xinji.agentplatform.consultation.taskcore.DevPurposeSelectionStore.PreviewCheck)
                (feature, focus) -> new Preview(id, 2, feature, "DEV-CON-COMPARE-INPUT-01",
                        "", List.of(), focus, set, 1, "P1", "v1", "a".repeat(64), "claim:1", "",
                        "NOT_SELECTED", "NOT_ASSESSED", List.of("ANALYSIS_PURPOSE", "PROFESSIONAL_METHOD"));
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        String winner;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> race(first, human, id, "purpose-dm8-select-123", set, guard, check, ready, go));
            var b = pool.submit(() -> race(restarted, human, id, "purpose-dm8-select-456", set, guard, check, ready, go));
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            go.countDown();
            String left = a.get(), right = b.get();
            assertThat(List.of(left, right)).contains("PURPOSE_ALREADY_SELECTED");
            assertThat(List.of(left, right).stream().filter(value -> value.startsWith("selected:")).count()).isEqualTo(1);
            winner = left.startsWith("selected:") ? "purpose-dm8-select-123" : "purpose-dm8-select-456";
        }
        var selected = first.select(human, id, winner, 2, 1, 1, set, 1,
                "TECHNICAL_SIMILARITY", guard, check);
        assertThat(selected.purposeSequence()).isEqualTo(1);
        assertThat(restarted.select(human, id, winner, 2, 1, 1, set, 1,
                "TECHNICAL_SIMILARITY", guard, check).receiptId()).isEqualTo(selected.receiptId());
        assertThatThrownBy(() -> restarted.select(human, id, "purpose-dm8-select-other", 2, 1, 1, set, 1,
                "TECHNICAL_SIMILARITY", guard, check)).isInstanceOf(DevTaskCoreFailure.class)
                .hasMessage("PURPOSE_ALREADY_SELECTED");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_purpose_revision WHERE task_id=?",
                Integer.class, id)).isEqualTo(1);
        assertThat(restarted.current(human, id, guard, check).purposeSequence()).isEqualTo(1);
        sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), id);
        assertThatThrownBy(() -> first.read(human, id, 1, guard, check))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_EXPIRED");
        assertThat(task.reconcile(job, guard).removed()).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_purpose_revision", Integer.class)).isZero();
    }

    private static String race(Dm8DevPurposeSelectionStore store, Actor human, String id, String key,
                               String set, Guard guard,
                               cn.xinji.agentplatform.consultation.taskcore.DevPurposeSelectionStore.PreviewCheck check,
                               CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        try {
            return "selected:" + store.select(human, id, key, 2, 1, 1, set, 1,
                    "TECHNICAL_SIMILARITY", guard, check).receiptId();
        } catch (DevTaskCoreFailure failure) {
            return failure.getMessage();
        }
    }
}
