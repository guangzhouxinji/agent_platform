package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.caselibrary.adapter.out.dm8.Dm8CaseAnnotationWorkflowStore;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService.Annotation;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService.Audit;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowService.Task;
import cn.xinji.agentplatform.consultation.caselibrary.application.CaseAnnotationWorkflowStore.VersionConflictException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class Dm8CaseAnnotationWorkflowStoreIntegrationTest {
    private String url;
    private String user;
    private String password;

    @BeforeEach void initialize() throws Exception {
        url = System.getenv("DM8_JDBC_URL"); user = System.getenv("DM8_JDBC_USER"); password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = connection()) { runIgnoring(connection, "db/dm8/U004__case_annotation_v0.sql"); run(connection, "db/dm8/V004__case_annotation_v0.sql"); }
    }

    @AfterEach void cleanup() throws Exception { if (url != null) try (Connection connection = connection()) { runIgnoring(connection, "db/dm8/U004__case_annotation_v0.sql"); } }

    @Test void roundTripsExactHistoryThroughNewStoreAndRecoversInterruptedTask() throws Exception {
        var first = store(); var seed = seed("one");
        first.createIfAbsent("v0-authorized-user", seed.task(), seed.annotation(), seed.audit());
        Annotation revised = version(seed.annotation(), 2, "DRAFT", "人工核对");
        first.append(1, revised, audit(revised, "REVISE"));
        var restarted = store();
        assertEquals(revised, restarted.current(revised.annotationId()).orElseThrow());
        assertEquals(List.of(seed.annotation(), revised), restarted.annotationsForCase("CASE-SYN-001"));
        assertEquals(List.of("EXTRACT", "REVISE"), restarted.auditsForCase("CASE-SYN-001").stream().map(Audit::action).toList());

        insertProcessingTask("task-interrupted", "ann-interrupted");
        assertEquals(1, restarted.failInterruptedTasks(Instant.parse("2026-09-16T08:05:00Z")));
        assertEquals("FAILED", scalarText("SELECT status FROM con_case_extract_task WHERE task_id='task-interrupted'"));
        assertEquals("RESTART_INTERRUPTED", scalarText("SELECT error_code FROM con_case_extract_task WHERE task_id='task-interrupted'"));
    }

    @Test void concurrentCreationIsAtomicAndIdempotentAcrossStores() throws Exception {
        Seed left = seed("left"), right = seed("right");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Task> one = executor.submit(() -> { start.await(); return store().createIfAbsent("v0-authorized-user", left.task(), left.annotation(), left.audit()); });
            Future<Task> two = executor.submit(() -> { start.await(); return store().createIfAbsent("v0-authorized-user", right.task(), right.annotation(), right.audit()); });
            start.countDown();
            assertEquals(one.get(10, TimeUnit.SECONDS).taskId(), two.get(10, TimeUnit.SECONDS).taskId());
        }
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM con_case_extract_task"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM con_case_annotation_version"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM con_case_annotation_audit"));
    }

    @Test void concurrentVersionConflictAllowsOneWinnerWithoutOrphans() throws Exception {
        Seed seed = seed("version"); store().createIfAbsent("v0-authorized-user", seed.task(), seed.annotation(), seed.audit());
        Annotation left = version(seed.annotation(), 2, "DRAFT", "左侧"), right = version(seed.annotation(), 2, "DRAFT", "右侧");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = List.of(left, right).stream().map(value -> executor.submit(() -> {
                start.await(); try { store().append(1, value, audit(value, "REVISE")); return true; } catch (VersionConflictException expected) { return false; }
            })).toList();
            start.countDown();
            long winners = 0; for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        }
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM con_case_annotation_version"));
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM con_case_annotation_audit"));
        assertEquals(2, scalarInt("SELECT current_version FROM con_case_annotation"));
    }

    @Test void constraintFailuresRollBackWholeCreateAndAppendTransactions() {
        Seed invalid = seed("invalid");
        Task invalidTask = new Task(invalid.task().taskId(), invalid.task().caseId(), invalid.task().sourceVersion(), invalid.task().config(), "UNKNOWN", 1, invalid.task().annotationId(), invalid.task().requestId(), invalid.task().updatedAt());
        assertThrows(IllegalStateException.class, () -> store().createIfAbsent("v0-authorized-user", invalidTask, invalid.annotation(), invalid.audit()));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM con_case_extract_task"));

        Seed valid = seed("valid"); store().createIfAbsent("v0-authorized-user", valid.task(), valid.annotation(), valid.audit());
        Annotation invalidVersion = version(valid.annotation(), 2, "UNKNOWN", "非法状态");
        assertThrows(IllegalStateException.class, () -> store().append(1, invalidVersion, audit(invalidVersion, "REVISE")));
        assertEquals(1, store().current(valid.annotation().annotationId()).orElseThrow().version());
        assertEquals(0, scalarInt("SELECT row_version FROM con_case_annotation"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM con_case_annotation_version"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM con_case_annotation_audit"));
    }

    private Seed seed(String suffix) {
        String annotationId = "ann-" + suffix, time = "2026-09-16T08:00:00Z";
        Task task = new Task("task-" + suffix, "CASE-SYN-001", "v1", "SYNTHETIC_EXTRACT_V1", "SUCCEEDED", 1, annotationId, "request-" + suffix, time);
        Annotation annotation = new Annotation(annotationId, "CASE-SYN-001", "v1", 1, "PENDING_REVIEW", "事实", "焦点", "要点", "第1页第1段", "v0-authorized-user", "合成提取生成", time);
        return new Seed(task, annotation, audit(annotation, "EXTRACT"));
    }
    private static Annotation version(Annotation previous, int version, String state, String reason) { return new Annotation(previous.annotationId(), previous.caseId(), previous.sourceVersion(), version, state, previous.fact(), previous.issue(), previous.holding(), previous.sourceLocation(), previous.actor(), reason, "2026-09-16T08:0" + version + ":00Z"); }
    private static Audit audit(Annotation value, String action) { return new Audit(value.annotationId(), action, value.actor(), value.reason(), value.version(), value.updatedAt()); }
    private Dm8CaseAnnotationWorkflowStore store() { return new Dm8CaseAnnotationWorkflowStore(() -> { try { return connection(); } catch (SQLException failure) { throw new IllegalStateException(failure); } }); }
    private Connection connection() throws SQLException { return DriverManager.getConnection(url, user, password); }
    private void insertProcessingTask(String taskId, String annotationId) throws SQLException { try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO con_case_extract_task VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { int i=1; Timestamp now=Timestamp.from(Instant.parse("2026-09-16T08:00:00Z")); statement.setString(i++,taskId);statement.setString(i++,"other-user");statement.setString(i++,"CASE-SYN-002");statement.setString(i++,"v1");statement.setString(i++,"SYNTHETIC_EXTRACT_V1");statement.setString(i++,"PROCESSING");statement.setInt(i++,1);statement.setString(i++,annotationId);statement.setString(i++,"request-interrupted");statement.setString(i++,"trace-interrupted");statement.setNull(i++,Types.VARCHAR);statement.setInt(i++,0);statement.setTimestamp(i++,now);statement.setTimestamp(i,now);statement.executeUpdate(); } }
    private int scalarInt(String sql) { try (Connection connection=connection(); Statement statement=connection.createStatement(); ResultSet rows=statement.executeQuery(sql)) { rows.next(); return rows.getInt(1); } catch(SQLException failure) { throw new IllegalStateException(failure); } }
    private String scalarText(String sql) { try (Connection connection=connection(); Statement statement=connection.createStatement(); ResultSet rows=statement.executeQuery(sql)) { rows.next(); return rows.getString(1); } catch(SQLException failure) { throw new IllegalStateException(failure); } }
    private static void run(Connection connection,String file)throws Exception{try(Statement statement=connection.createStatement()){for(String sql:Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)"))if(!sql.isBlank())statement.execute(sql);}}
    private static void runIgnoring(Connection connection,String file)throws Exception{try{run(connection,file);}catch(SQLException ignored){}}
    private record Seed(Task task, Annotation annotation, Audit audit) { }
}
