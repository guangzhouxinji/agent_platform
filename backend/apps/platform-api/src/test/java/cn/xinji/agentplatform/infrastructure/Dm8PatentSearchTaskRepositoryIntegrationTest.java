package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.dm8.Dm8PatentSearchTaskRepository;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCandidate;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Dm8PatentSearchTaskRepositoryIntegrationTest {
    private String url;
    private String user;
    private String password;

    @BeforeEach void initialize() throws Exception {
        url = System.getenv("DM8_JDBC_URL");
        user = System.getenv("DM8_JDBC_USER");
        password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = connection()) {
            executeIgnoring(connection, "db/dm8/U001__consultation_mvp_v0.sql");
            execute(connection, "db/dm8/V001__consultation_mvp_v0.sql");
        }
    }

    @AfterEach void cleanup() throws Exception {
        if (url == null) return;
        try (Connection connection = connection()) { executeIgnoring(connection, "db/dm8/U001__consultation_mvp_v0.sql"); }
    }

    @Test void roundTripsCompleteTerminalTaskAndStableCandidateOrderThroughNewRepository() {
        var repository = repository();
        PatentSearchTask queued = task("roundtrip", "key-roundtrip");
        repository.saveIfAbsent(queued);
        PatentSearchTask completed = repository.update(queued.taskId(), current -> current.succeeded(
                "中国发明专利；2020-01-01 至 2025-12-31", List.of(candidate("SYN-002", 88), candidate("SYN-001", 88)),
                "仅为合成数据功能验证。", false, at(2)));

        PatentSearchTask restored = repository().findById(completed.taskId()).orElseThrow();
        assertEquals(completed, restored);
        assertEquals(List.of("SYN-002", "SYN-001"), restored.candidates().stream().map(PatentSearchCandidate::sourceId).toList());
        assertEquals(restored, repository().findByIdempotency("v0-authorized-user", "key-roundtrip").orElseThrow());
        assertEquals(List.of(restored), repository().findByRequester("v0-authorized-user"));
    }

    @Test void duplicateIdempotencyKeyReturnsExistingWithoutOrphanTaskOrCandidates() {
        var repository = repository();
        PatentSearchTask original = task("original", "same-key");
        repository.saveIfAbsent(original);
        PatentSearchTask duplicate = task("duplicate", "same-key").succeeded("scope", List.of(candidate("SYN-X", 70)), "test", false, at(2));

        assertEquals(original, repository.saveIfAbsent(duplicate));
        assertTrue(repository.findById(duplicate.taskId()).isEmpty());
        assertEquals(1, count("con_patent_search_task"));
        assertEquals(0, count("con_patent_search_candidate"));
    }

    @Test void candidateConstraintFailureRollsBackTaskStateVersionAndCandidateReplacement() {
        var repository = repository();
        PatentSearchTask queued = task("rollback", "rollback-key");
        repository.saveIfAbsent(queued);
        PatentSearchCandidate duplicate = candidate("SYN-DUP", 80);

        assertThrows(IllegalStateException.class, () -> repository.update(queued.taskId(), current ->
                current.succeeded("scope", List.of(duplicate, duplicate), "must rollback", false, at(2))));

        assertEquals(queued, repository().findById(queued.taskId()).orElseThrow());
        assertEquals(0, count("con_patent_search_candidate"));
        assertEquals(0, scalar("SELECT row_version FROM con_patent_search_task WHERE task_id='" + queued.taskId() + "'"));
    }

    @Test void concurrentUpdatesExposeOptimisticVersionConflict() throws Exception {
        var repository = repository();
        PatentSearchTask queued = task("concurrent", "concurrent-key");
        repository.saveIfAbsent(queued);
        CyclicBarrier bothLoaded = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<PatentSearchTask> update = () -> repository.update(queued.taskId(), current -> {
                try { bothLoaded.await(10, TimeUnit.SECONDS); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
                return current.processing(at(2));
            });
            Future<PatentSearchTask> first = executor.submit(update);
            Future<PatentSearchTask> second = executor.submit(update);
            int successes = 0;
            int conflicts = 0;
            for (Future<PatentSearchTask> future : List.of(first, second)) {
                try { future.get(20, TimeUnit.SECONDS); successes++; }
                catch (ExecutionException exception) {
                    assertInstanceOf(Dm8PatentSearchTaskRepository.VersionConflictException.class, exception.getCause());
                    conflicts++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, conflicts);
            assertEquals(1, scalar("SELECT row_version FROM con_patent_search_task WHERE task_id='" + queued.taskId() + "'"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void concurrentRepositoriesReturnOneTaskForSameIdempotencyKey() throws Exception {
        var firstRepository = repository();
        var secondRepository = repository();
        PatentSearchTask firstTask = task("instance-a", "shared-instance-key");
        PatentSearchTask secondTask = new PatentSearchTask(task("instance-b", "shared-instance-key").taskId(),
                firstTask.requester(), firstTask.idempotencyKey(), firstTask.requestFingerprint(),
                firstTask.inputSnapshot(), firstTask.status(), firstTask.attempt(), firstTask.dataMode(),
                firstTask.scoringVersion(), firstTask.actualScope(), firstTask.candidates(), firstTask.limitation(),
                firstTask.errorCode(), firstTask.traceId(), firstTask.createdAt(), firstTask.updatedAt());
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PatentSearchTask> first = executor.submit(() -> { start.await(); return firstRepository.saveIfAbsent(firstTask); });
            Future<PatentSearchTask> second = executor.submit(() -> { start.await(); return secondRepository.saveIfAbsent(secondTask); });
            PatentSearchTask left = first.get(20, TimeUnit.SECONDS);
            PatentSearchTask right = second.get(20, TimeUnit.SECONDS);
            assertEquals(left.taskId(), right.taskId());
            assertEquals(1, count("con_patent_search_task"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void newRepositoryMarksInterruptedTaskFailedAndPreservesRetryableSnapshot() {
        var firstProcess = repository();
        PatentSearchTask queued = task("restart", "restart-key");
        firstProcess.saveIfAbsent(queued);
        firstProcess.update(queued.taskId(), current -> current.processing(at(2)));

        var restarted = repository();
        assertEquals(1, restarted.failInterruptedTasks(at(3)));
        PatentSearchTask recovered = restarted.findById(queued.taskId()).orElseThrow();
        assertEquals(PatentSearchTask.Status.FAILED, recovered.status());
        assertEquals("RESTART_INTERRUPTED", recovered.errorCode());
        assertEquals(queued.inputSnapshot(), recovered.inputSnapshot());
        assertEquals(1, recovered.attempt());
        assertEquals(2, restarted.update(queued.taskId(), task -> task.requeued(at(4))).attempt());
    }

    private PatentSearchTask task(String seed, String key) {
        PatentSearchCommand.SearchScope scope = new PatentSearchCommand.SearchScope(
                List.of("发明", "实用新型"), List.of("CN"), List.of("H01M", "B05D"),
                LocalDate.parse("2020-01-01"), LocalDate.parse("2025-12-31"),
                LocalDate.parse("2021-01-01"), LocalDate.parse("2026-01-31"));
        PatentSearchCommand command = new PatentSearchCommand(PatentSearchCommand.InputMode.CORE_FEATURES,
                "水性粘结剂\n双区烘干", PatentSearchCommand.TECHNICAL_REFERENCE, List.of("水性粘结剂", "双区烘干"), scope);
        return PatentSearchTask.queued(UUID.nameUUIDFromBytes(seed.getBytes()).toString(), "v0-authorized-user", key,
                "fingerprint-" + seed, command, "SYNTHETIC", "SYNTHETIC-RANK-V1", "synthetic only", at(1));
    }

    private static PatentSearchCandidate candidate(String source, int score) {
        return new PatentSearchCandidate(source, "APP-" + source, "PUB-" + source, "合成专利 " + source,
                "发明", "synthetic-v1", score, "高度相关", List.of("水性粘结剂", "双区烘干"),
                "合成匹配片段\n仅用于测试", "权利要求 1");
    }

    private Dm8PatentSearchTaskRepository repository() {
        return new Dm8PatentSearchTaskRepository(() -> {
            try { return connection(); }
            catch (SQLException exception) { throw new IllegalStateException(exception); }
        });
    }

    private Connection connection() throws SQLException { return DriverManager.getConnection(url, user, password); }
    private static Instant at(int minute) { return Instant.parse("2026-09-16T01:0" + minute + ":00Z"); }

    private int count(String table) { return scalar("SELECT COUNT(*) FROM " + table); }
    private int scalar(String sql) {
        try (Connection connection = connection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next(); return result.getInt(1);
        } catch (SQLException exception) { throw new IllegalStateException(exception); }
    }

    private static void execute(Connection connection, String file) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }
    private static void executeIgnoring(Connection connection, String file) throws Exception {
        try { execute(connection, file); } catch (SQLException ignored) { }
    }
}
