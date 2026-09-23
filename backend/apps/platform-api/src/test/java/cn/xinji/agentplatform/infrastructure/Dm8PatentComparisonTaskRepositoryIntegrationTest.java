package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.patentcomparison.adapter.out.dm8.Dm8PatentComparisonTaskRepository;
import cn.xinji.agentplatform.consultation.patentcomparison.domain.PatentComparisonCommand;
import cn.xinji.agentplatform.consultation.patentcomparison.domain.PatentComparisonTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class Dm8PatentComparisonTaskRepositoryIntegrationTest {
    private String url;
    private String user;
    private String password;

    @BeforeEach void initialize() throws Exception {
        url = System.getenv("DM8_JDBC_URL"); user = System.getenv("DM8_JDBC_USER"); password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = connection()) { runIgnoring(connection, "db/dm8/U005__patent_comparison_v0.sql"); run(connection, "db/dm8/V005__patent_comparison_v0.sql"); }
    }

    @AfterEach void cleanup() throws Exception {
        if (url != null) try (Connection connection = connection()) { runIgnoring(connection, "db/dm8/U005__patent_comparison_v0.sql"); }
    }

    @Test void roundTripsExactSnapshotThroughNewRepository() {
        PatentComparisonTask expected = task("one", "key-one", "fingerprint-one");
        repository().saveIfAbsent(expected);
        var restarted = repository();
        assertEquals(expected, restarted.findById(expected.taskId()).orElseThrow());
        assertEquals(List.of(expected), restarted.findByRequester(expected.requester()));
        assertTrue(restarted.findByRequester("other-user").isEmpty());
    }

    @Test void concurrentStoresUseDatabaseForAtomicIdempotency() throws Exception {
        PatentComparisonTask left = task("left", "shared-key", "fingerprint-left");
        PatentComparisonTask right = task("right", "shared-key", "fingerprint-right");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            Future<PatentComparisonTask> one = executor.submit(() -> { start.await(); return repository().saveIfAbsent(left); });
            Future<PatentComparisonTask> two = executor.submit(() -> { start.await(); return repository().saveIfAbsent(right); });
            start.countDown();
            PatentComparisonTask first = one.get(10, TimeUnit.SECONDS), second = two.get(10, TimeUnit.SECONDS);
            assertEquals(first.taskId(), second.taskId());
            assertEquals(first.requestFingerprint(), second.requestFingerprint());
        }
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM con_patent_compare_task"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM con_patent_compare_candidate"));
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM con_patent_compare_feature"));
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM con_patent_compare_mapping"));
    }

    @Test void detailFailureRollsBackWholeTask() {
        PatentComparisonTask valid = task("valid", "rollback-key", "fingerprint");
        var invalidMapping = new PatentComparisonTask.FeatureMapping("MISSING", "未知", "CORRESPONDS", "证据", "位置", "", "原因");
        var invalidPair = new PatentComparisonTask.PairResult(valid.pairs().getFirst().candidate(), "COMPLETED", List.of(invalidMapping), List.of(), List.of(), List.of(), "辅助意见");
        PatentComparisonTask invalid = new PatentComparisonTask(valid.taskId(), valid.requester(), valid.idempotencyKey(), valid.requestFingerprint(),
                valid.inputSnapshot(), valid.status(), valid.dataMode(), valid.sourceVersion(), valid.ruleVersion(), valid.scoreCapability(),
                valid.reviewStatus(), List.of(invalidPair), valid.limitations(), valid.traceId(), valid.createdAt());
        assertThrows(IllegalStateException.class, () -> repository().saveIfAbsent(invalid));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM con_patent_compare_task"));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM con_patent_compare_candidate"));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM con_patent_compare_feature"));
    }

    private PatentComparisonTask task(String suffix, String key, String fingerprint) {
        var target = new PatentComparisonCommand.DocumentRef("SYN-PAT-001", "synthetic-v1");
        var candidate = new PatentComparisonCommand.DocumentRef("SYN-PAT-003", "synthetic-v1");
        var features = List.of(new PatentComparisonCommand.Feature("F1", "双区烘干", "目标权利要求1"),
                new PatentComparisonCommand.Feature("F2", "水性粘结剂", "目标权利要求1"));
        var command = new PatentComparisonCommand(PatentComparisonCommand.Purpose.CLAIM_COMPARISON, target, List.of(candidate), features);
        var mappings = List.of(
                new PatentComparisonTask.FeatureMapping("F1", "双区烘干", "CORRESPONDS", "包含双区烘干", "候选权利要求1", "未发现逐字差异", "逐字核对"),
                new PatentComparisonTask.FeatureMapping("F2", "水性粘结剂", "NOT_FOUND", null, null, "候选权利要求中未找到", "逐字核对"));
        var pair = new PatentComparisonTask.PairResult(candidate, "COMPLETED", mappings, List.of("双区烘干"), List.of("候选权利要求中未找到"), List.of(), "仅提供证据表");
        return new PatentComparisonTask("PC-" + suffix, "v0-authorized-user", key, fingerprint, command, "SUCCEEDED", "SYNTHETIC_V0",
                "catalog-v1", "SYNTHETIC_CLAIM_COMPARE_V1", "NOT_CALIBRATED", "UNREVIEWED", List.of(pair),
                List.of("限制一", "限制二"), "trace-" + suffix, Instant.parse("2026-09-16T08:00:00Z"));
    }

    private Dm8PatentComparisonTaskRepository repository() { return new Dm8PatentComparisonTaskRepository(() -> { try { return connection(); } catch (SQLException failure) { throw new IllegalStateException(failure); } }); }
    private Connection connection() throws SQLException { return DriverManager.getConnection(url, user, password); }
    private int scalarInt(String sql) { try (Connection connection=connection(); Statement statement=connection.createStatement(); ResultSet rows=statement.executeQuery(sql)) { rows.next(); return rows.getInt(1); } catch(SQLException failure) { throw new IllegalStateException(failure); } }
    private static void run(Connection connection,String file)throws Exception{try(Statement statement=connection.createStatement()){for(String sql:Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)"))if(!sql.isBlank())statement.execute(sql);}}
    private static void runIgnoring(Connection connection,String file)throws Exception{try{run(connection,file);}catch(SQLException ignored){}}
}
