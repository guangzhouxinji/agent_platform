package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EnforcementStore;
import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.SyntheticEnforcementSourceAdapter;
import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.V0EnforcementAuthorizationAdapter;
import cn.xinji.agentplatform.enforcement.application.EnforcementApplicationService;
import cn.xinji.agentplatform.enforcement.application.EnforcementApplicationService.EnforcementException;
import cn.xinji.agentplatform.enforcement.domain.ClueAnalysisVersion;
import cn.xinji.agentplatform.enforcement.domain.EnforcementSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Dm8EnforcementStoreIntegrationTest {
    private static final String USER = "v0-enforcement-user";
    private static final String REVIEWER = "v0-enforcement-reviewer";
    private String url;
    private String user;
    private String password;

    @BeforeEach
    void initializeSchema() throws Exception {
        url = System.getenv("DM8_JDBC_URL");
        user = System.getenv("DM8_JDBC_USER");
        password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = connection()) {
            executeIgnoring(connection, "db/dm8/U002__enforcement_mvp_v0.sql");
            execute(connection, "db/dm8/V002__enforcement_mvp_v0.sql");
        }
    }

    @AfterEach
    void cleanupSchema() throws Exception {
        if (url == null) return;
        try (Connection connection = connection()) {
            executeIgnoring(connection, "db/dm8/U002__enforcement_mvp_v0.sql");
        }
    }

    @Test
    void writesNormalizedHistoryReplaysExactSnapshotAndRestoresAfterRestart() {
        Fixture fixture = fixture();
        EnforcementSnapshot opened = fixture.service().openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "open-normal");
        EnforcementSnapshot processed = fixture.service().process(USER, opened.workItem().workItemId(),
                List.of("V0-SYN-EVD-001", "V0-SYN-EVD-002"), "process-normal");
        EnforcementSnapshot reviewed = fixture.service().reviewLatestAnalysis(REVIEWER, opened.workItem().workItemId(),
                1, "CONFIRM", "checked", "review-normal");

        Dm8EnforcementStore restarted = store();
        assertEquals(reviewed, restarted.findByWorkItemId(opened.workItem().workItemId()).orElseThrow().snapshot());
        assertEquals(opened, restarted.findByIdempotency(USER, "OPEN", "open-normal").orElseThrow().snapshot());
        assertEquals(processed, restarted.findByIdempotency(USER, "PROCESS", "process-normal").orElseThrow().snapshot());
        assertTrue(tableCount() >= 19, "V0 tables must remain present alongside later enforcement migrations");
    }

    @Test
    void incrementsEvidenceVersionAndRestoresUnavailableSourceImpact() {
        Fixture fixture = fixture();
        EnforcementSnapshot opened = fixture.service().openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "open-change");
        fixture.service().process(USER, opened.workItem().workItemId(), List.of("V0-SYN-EVD-006"), "process-change-1");
        fixture.service().reviewLatestAnalysis(REVIEWER, opened.workItem().workItemId(), 1, "CONFIRM", "checked", "review-change");
        fixture.sources().markUnavailable("V0-SYN-EVD-006");
        EnforcementSnapshot changed = fixture.service().process(USER, opened.workItem().workItemId(), List.of("V0-SYN-EVD-006"), "process-change-2");

        assertEquals(List.of(1, 2), changed.evidenceReferences().stream().map(value -> value.version()).toList());
        assertFalse(changed.reviewImpacts().isEmpty());
        assertEquals(changed, store().findByWorkItemId(opened.workItem().workItemId()).orElseThrow().snapshot());
    }

    @Test
    void restoresConflictCandidatesMissingCollectionAndUnprocessedEvidence() {
        Fixture fixture = fixture();
        EnforcementSnapshot opened = fixture.service().openWork(USER, "org-v0", "V0-SYN-CLUE-002", "v1", "open-conflict");
        EnforcementSnapshot result = fixture.service().process(USER, opened.workItem().workItemId(),
                List.of("V0-SYN-EVD-003", "V0-SYN-EVD-004", "V0-SYN-EVD-007"), "process-conflict");

        EnforcementSnapshot restored = store().findByWorkItemId(opened.workItem().workItemId()).orElseThrow().snapshot();
        assertEquals(result, restored);
        assertEquals(List.of("2026-08-03", "2026-08-12"), restored.clueAnalyses().getFirst().conflicts().getFirst()
                .candidates().stream().map(ClueAnalysisVersion.SourcedValue::value).toList());
        assertTrue(restored.evidenceReferences().stream().allMatch(value -> value.collector() == null
                && "NOT_PROVIDED".equals(value.collectionMissingReason())));
        assertEquals(List.of("V0-SYN-EVD-007:UNSUPPORTED_CONTENT"), restored.relationSets().getFirst().unprocessedItems());
    }

    @Test
    void appendsHumanRevisionAndReviewAndRejectsStaleVersion() {
        Fixture fixture = fixture();
        EnforcementSnapshot opened = fixture.service().openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "open-review");
        fixture.service().process(USER, opened.workItem().workItemId(), List.of("V0-SYN-EVD-001"), "process-review");
        EnforcementSnapshot revised = fixture.service().reviseClue(REVIEWER, opened.workItem().workItemId(), 1, "checked", "revise-review");
        EnforcementException stale = assertThrows(EnforcementException.class,
                () -> fixture.service().reviseClue(REVIEWER, opened.workItem().workItemId(), 1, "stale", "revise-stale"));
        assertEquals("VERSION_CONFLICT", stale.code());
        EnforcementSnapshot reviewed = fixture.service().reviewLatestAnalysis(REVIEWER, opened.workItem().workItemId(),
                1, "RETURN", "needs another check", "human-review");

        assertEquals(2, revised.clueAnalyses().size());
        assertEquals(reviewed, store().findByWorkItemId(opened.workItem().workItemId()).orElseThrow().snapshot());
        assertEquals("SYNTHETIC_PROCESS_TEST", reviewed.reviews().getFirst().scopeLabel());
        assertTrue(reviewed.reviews().getFirst().notOfficialDecision());
    }

    @Test
    void concurrentSameKeyReturnsOneSnapshotAndChangedFingerprintConflicts() throws Exception {
        Fixture fixture = fixture();
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<EnforcementSnapshot>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) futures.add(executor.submit(() -> {
                start.await();
                return fixture.service().openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "open-concurrent");
            }));
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (Future<EnforcementSnapshot> future : futures) ids.add(future.get(30, TimeUnit.SECONDS).workItem().workItemId());
            assertEquals(1, ids.stream().distinct().count());
            assertEquals(1, rowCount("enf_work_item"));
            assertEquals(1, rowCount("enf_idempotency_record"));
        } finally {
            executor.shutdownNow();
        }
        EnforcementException conflict = assertThrows(EnforcementException.class,
                () -> fixture.service().openWork(USER, "other-org", "V0-SYN-CLUE-001", "v1", "open-concurrent"));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());
    }

    @Test
    void childConstraintFailureRollsBackHistoryVersionAndIdempotency() {
        Fixture fixture = fixture();
        EnforcementSnapshot opened = fixture.service().openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "open-rollback");
        ClueAnalysisVersion invalid = new ClueAnalysisVersion(uuid("invalid-analysis"), 1, opened.workItem().clueRef(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "TO_BE_EVALUATED",
                null, "INVALID_REVISION", "synthetic", Instant.parse("2026-09-15T08:00:00Z"), "SYNTHETIC_V0");
        EnforcementSnapshot invalidSnapshot = new EnforcementSnapshot(opened.workItem(), List.of(invalid), List.of(), List.of(), List.of(), List.of(), List.of());

        assertThrows(IllegalStateException.class,
                () -> fixture.store().save(USER, "PROCESS", "rollback-child", "rollback-fingerprint", invalidSnapshot));
        assertEquals(opened, store().findByWorkItemId(opened.workItem().workItemId()).orElseThrow().snapshot());
        assertTrue(store().findByIdempotency(USER, "PROCESS", "rollback-child").isEmpty());
        assertEquals(0, rowCount("enf_clue_analysis"));
    }

    private Fixture fixture() {
        Dm8EnforcementStore store = store();
        SyntheticEnforcementSourceAdapter sources = new SyntheticEnforcementSourceAdapter();
        AtomicInteger sequence = new AtomicInteger();
        EnforcementApplicationService service = new EnforcementApplicationService(store, new V0EnforcementAuthorizationAdapter(), sources,
                Clock.fixed(Instant.parse("2026-09-15T08:00:00Z"), ZoneOffset.UTC), () -> uuid("entity-" + sequence.incrementAndGet()));
        return new Fixture(service, store, sources);
    }

    private Dm8EnforcementStore store() {
        return new Dm8EnforcementStore(() -> {
            try {
                return connection();
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private int rowCount(String table) {
        try (Connection connection = connection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int tableCount() {
        try (Connection connection = connection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME LIKE 'ENF_%'")) {
            result.next();
            return result.getInt(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void execute(Connection connection, String file) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }

    private static void executeIgnoring(Connection connection, String file) throws Exception {
        try {
            execute(connection, file);
        } catch (SQLException ignored) {
        }
    }

    private record Fixture(EnforcementApplicationService service, Dm8EnforcementStore store, SyntheticEnforcementSourceAdapter sources) {}
}
