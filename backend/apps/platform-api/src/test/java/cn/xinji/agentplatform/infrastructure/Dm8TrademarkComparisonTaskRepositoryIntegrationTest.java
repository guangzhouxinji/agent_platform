package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.trademarkcomparison.adapter.out.dm8.Dm8TrademarkComparisonTaskRepository;
import cn.xinji.agentplatform.consultation.trademarkcomparison.domain.TrademarkComparisonCommand;
import cn.xinji.agentplatform.consultation.trademarkcomparison.domain.TrademarkComparisonTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class Dm8TrademarkComparisonTaskRepositoryIntegrationTest {
    private String url;
    private String user;
    private String password;

    @BeforeEach void initialize() throws Exception {
        url = System.getenv("DM8_JDBC_URL"); user = System.getenv("DM8_JDBC_USER"); password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = connection()) { runIgnoring(connection, "db/dm8/U006__trademark_comparison_v0.sql"); run(connection, "db/dm8/V006__trademark_comparison_v0.sql"); }
    }

    @AfterEach void cleanup() throws Exception {
        if (url != null) try (Connection connection = connection()) { runIgnoring(connection, "db/dm8/U006__trademark_comparison_v0.sql"); }
    }

    @Test void roundTripsExactSnapshotThroughNewRepository() {
        TrademarkComparisonTask expected = task("one", "key-one", "fingerprint-one");
        repository().saveIfAbsent(expected);
        var restarted = repository();
        assertEquals(expected, restarted.findById(expected.taskId()).orElseThrow());
        assertEquals(List.of(expected), restarted.findByRequester(expected.requester()));
        assertTrue(restarted.findByRequester("other-user").isEmpty());
    }

    @Test void concurrentStoresUseDatabaseForAtomicIdempotency() throws Exception {
        TrademarkComparisonTask left = task("left", "shared-key", "fingerprint-left");
        TrademarkComparisonTask right = task("right", "shared-key", "fingerprint-right");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            Future<TrademarkComparisonTask> one = executor.submit(() -> { start.await(); return repository().saveIfAbsent(left); });
            Future<TrademarkComparisonTask> two = executor.submit(() -> { start.await(); return repository().saveIfAbsent(right); });
            start.countDown();
            TrademarkComparisonTask first = one.get(10, TimeUnit.SECONDS), second = two.get(10, TimeUnit.SECONDS);
            assertEquals(first.taskId(), second.taskId());
            assertEquals(first.requestFingerprint(), second.requestFingerprint());
        }
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM con_trademark_compare_task"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM con_trademark_compare_candidate"));
        assertEquals(3, scalarInt("SELECT COUNT(*) FROM con_trademark_compare_dimension"));
    }

    @Test void detailFailureRollsBackWholeTask() {
        TrademarkComparisonTask valid = task("rollback", "rollback-key", "fingerprint");
        var invalidDimension = new TrademarkComparisonTask.DimensionResult(null, "ANALYZED", "观察", List.of("证据"), "原因");
        var validPair = valid.pairs().getFirst();
        var invalidPair = new TrademarkComparisonTask.PairResult(validPair.candidate(), validPair.status(), List.of(invalidDimension), validPair.missingItems(), validPair.auxiliaryOpinion());
        TrademarkComparisonTask invalid = new TrademarkComparisonTask(valid.taskId(), valid.requester(), valid.idempotencyKey(), valid.requestFingerprint(),
                valid.inputSnapshot(), valid.status(), valid.dataMode(), valid.sourceVersion(), valid.ruleVersion(), valid.reportCapability(),
                valid.reviewStatus(), List.of(invalidPair), valid.limitations(), valid.traceId(), valid.createdAt());
        assertThrows(IllegalStateException.class, () -> repository().saveIfAbsent(invalid));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM con_trademark_compare_task"));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM con_trademark_compare_candidate"));
    }

    private TrademarkComparisonTask task(String suffix, String key, String fingerprint) {
        var target = new TrademarkComparisonCommand.DocumentRef("TM-SYN-001", "synthetic-v1");
        var candidate = new TrademarkComparisonCommand.DocumentRef("TM-SYN-002", "synthetic-v1");
        var dimensions = List.of(TrademarkComparisonCommand.Dimension.TEXT, TrademarkComparisonCommand.Dimension.PHONETIC, TrademarkComparisonCommand.Dimension.SEMANTIC);
        var command = new TrademarkComparisonCommand(TrademarkComparisonCommand.Purpose.AUXILIARY_COMPARISON, target, List.of(candidate),
                List.of("3"), List.of("化妆品", "洗发液"), dimensions);
        var results = List.of(
                new TrademarkComparisonTask.DimensionResult(TrademarkComparisonCommand.Dimension.TEXT, "ANALYZED", "共同文字：清", List.of("目标名称：清禾", "候选名称：清和"), "仅记录文字事实"),
                new TrademarkComparisonTask.DimensionResult(TrademarkComparisonCommand.Dimension.PHONETIC, "ANALYZED", "受控读音相同", List.of("qing he"), "不处理多音字"),
                new TrademarkComparisonTask.DimensionResult(TrademarkComparisonCommand.Dimension.SEMANTIC, "NOT_ANALYZED", "未配置模型", List.of(), "MODEL_UNAVAILABLE"));
        var pair = new TrademarkComparisonTask.PairResult(candidate, "PARTIAL", results, List.of("MODEL_UNAVAILABLE"), "仅提供逐维证据");
        return new TrademarkComparisonTask("TC-" + suffix, "v0-authorized-user", key, fingerprint, command, "PARTIAL", "SYNTHETIC_V0",
                "catalog-v1", "SYNTHETIC_TRADEMARK_OBSERVATION_V1", "EVIDENCE_TABLE_ONLY", "UNREVIEWED", List.of(pair),
                List.of("限制一", "限制二"), "trace-" + suffix, Instant.parse("2026-09-16T08:00:00Z"));
    }

    private Dm8TrademarkComparisonTaskRepository repository() { return new Dm8TrademarkComparisonTaskRepository(() -> { try { return connection(); } catch (SQLException failure) { throw new IllegalStateException(failure); } }); }
    private Connection connection() throws SQLException { return DriverManager.getConnection(url, user, password); }
    private int scalarInt(String sql) { try (Connection connection=connection(); Statement statement=connection.createStatement(); ResultSet rows=statement.executeQuery(sql)) { rows.next(); return rows.getInt(1); } catch(SQLException failure) { throw new IllegalStateException(failure); } }
    private static void run(Connection connection,String file)throws Exception{try(Statement statement=connection.createStatement()){for(String sql:Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)"))if(!sql.isBlank())statement.execute(sql);}}
    private static void runIgnoring(Connection connection,String file)throws Exception{try{run(connection,file);}catch(SQLException ignored){}}
}
