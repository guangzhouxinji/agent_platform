package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.adjudication.adapter.out.dm8.Dm8AdjudicationStore;
import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.SyntheticAdjudicationSourceAdapter;
import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.V0AdjudicationAuthorizationAdapter;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService;
import cn.xinji.agentplatform.adjudication.application.AdjudicationStore;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class Dm8AdjudicationStoreIntegrationTest {
    private static final String USER = "v0-adjudication-user";
    private static final String REVIEWER = "v0-adjudication-reviewer";
    private String url;
    private String user;
    private String password;

    @BeforeEach
    void resetSchema() throws Exception {
        url = System.getenv("DM8_JDBC_URL"); user = System.getenv("DM8_JDBC_USER"); password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = connection()) {
            executeIgnoring(connection, "db/dm8/U003__adjudication_mvp_v0.sql");
            execute(connection, "db/dm8/V003__adjudication_mvp_v0.sql");
        }
    }

    @Test
    void roundTripsAllSyntheticHistoryAndExactVersions() {
        Fixture fixture = fixture(store(), "normal");
        AdjudicationSnapshot opened = fixture.service.openWork(USER, "org-v0", "V0-SYN-ADJ-CASE-001", "v1",
                List.of("V0-SYN-ADJ-MAT-001"), "open");
        String workId = opened.work().workItemId();
        fixture.service.inspectMaterials(USER, workId, "inspect");
        fixture.service.matchLegalCandidates(USER, workId, 1, "match");
        fixture.service.cite(REVIEWER, workId, 0, "V0-SYN-ADJ-LAW-001", "SELECT", "test selection", "cite");
        fixture.service.createDraft(USER, workId, 1, "draft");
        fixture.service.reviseDraft(REVIEWER, workId, 1, "人工合成修订内容", "test revision", "revise");
        AdjudicationSnapshot expected = fixture.service.reviewDraft(REVIEWER, workId, 2, "CONFIRM", "test review", "review");

        Dm8AdjudicationStore restarted = store();
        assertEquals(expected, restarted.findByWorkItemId(workId).orElseThrow().snapshot());
        assertEquals(7, restarted.findByWorkItemId(workId).orElseThrow().persistenceVersion());
        AdjudicationApplicationService recovered = service(restarted, "restarted");
        Draft original = (Draft) recovered.readVersion(USER, workId, "drafts", expected.drafts().getFirst().draftId(), 1);
        assertEquals("合成草稿：待核实事项和来源映射已保留。", original.sections().getFirst().content());
        assertEquals(2, expected.drafts().size());
        assertTrue(expected.reviews().getFirst().notOfficialDecision());
        assertEquals(1, rowCount("adj_idempotency_record", "work_item_id", workId, "operation_code", "REVIEW"));
    }

    @Test
    void replaysSameKeyAcrossRepositoryInstancesAndRejectsDifferentFingerprint() {
        AdjudicationApplicationService first = service(store(), "first");
        AdjudicationSnapshot opened = first.openWork(USER, "org-v0", "V0-SYN-ADJ-CASE-001", "v1",
                List.of("V0-SYN-ADJ-MAT-001"), "shared-open");
        AdjudicationApplicationService second = service(store(), "second");
        assertEquals(opened, second.openWork(USER, "org-v0", "V0-SYN-ADJ-CASE-001", "v1",
                List.of("V0-SYN-ADJ-MAT-001"), "shared-open"));
        var conflict = assertThrows(AdjudicationApplicationService.AdjudicationException.class,
                () -> second.openWork(USER, "different-org", "V0-SYN-ADJ-CASE-001", "v1",
                        List.of("V0-SYN-ADJ-MAT-001"), "shared-open"));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());
        assertEquals(1, tableCount("adj_work_item"));
    }

    @Test
    void concurrentStoresAllowAtMostOneUpdateFromTheSamePersistenceVersion() throws Exception {
        Dm8AdjudicationStore initialStore = store();
        AdjudicationSnapshot opened = service(initialStore, "seed").openWork(USER, "org-v0", "V0-SYN-ADJ-CASE-001", "v1",
                List.of("V0-SYN-ADJ-MAT-001"), "open-concurrent");
        AdjudicationStore.StoredWork base = initialStore.findByWorkItemId(opened.work().workItemId()).orElseThrow();
        AdjudicationSnapshot left = appendInspection(base.snapshot(), "left");
        AdjudicationSnapshot right = appendInspection(base.snapshot(), "right");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = executor.submit(() -> saveAfter(start, store(), "LEFT", "left", base.persistenceVersion(), left));
            Future<Boolean> b = executor.submit(() -> saveAfter(start, store(), "RIGHT", "right", base.persistenceVersion(), right));
            start.countDown();
            assertEquals(1, List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)).stream().filter(Boolean::booleanValue).count());
        } finally {
            executor.shutdownNow();
        }
        AdjudicationStore.StoredWork result = store().findByWorkItemId(opened.work().workItemId()).orElseThrow();
        assertEquals(2, result.persistenceVersion());
        assertEquals(1, result.snapshot().inspections().size());
    }

    @Test
    void constraintFailureRollsBackVersionDetailsAndIdempotency() {
        Dm8AdjudicationStore repository = store();
        AdjudicationSnapshot opened = service(repository, "rollback").openWork(USER, "org-v0", "V0-SYN-ADJ-CASE-001", "v1",
                List.of("V0-SYN-ADJ-MAT-001"), "open-rollback");
        AdjudicationStore.StoredWork base = repository.findByWorkItemId(opened.work().workItemId()).orElseThrow();
        Source template = new Source("synthetic-adjudication-template", "V0-SYN-ADJ-TPL-001", "v1", "structure");
        Draft invalid = new Draft(uuid("invalid-draft"), 1, uuid("missing-inspection") + "@1", List.of(), template,
                List.of(), List.of(), List.of(), null, Instant.parse("2026-09-16T06:00:00Z"), true);
        AdjudicationSnapshot broken = new AdjudicationSnapshot(base.snapshot().work(), List.of(), List.of(), List.of(),
                List.of(invalid), List.of(), List.of());

        assertThrows(IllegalStateException.class,
                () -> repository.save(USER, "BROKEN", "rollback-key", "broken", base.persistenceVersion(), broken));
        AdjudicationStore.StoredWork recovered = repository.findByWorkItemId(opened.work().workItemId()).orElseThrow();
        assertEquals(1, recovered.persistenceVersion());
        assertTrue(recovered.snapshot().drafts().isEmpty());
        assertTrue(repository.findByIdempotency(USER, "BROKEN", "rollback-key").isEmpty());
    }

    @Test
    void conflictAndUnavailableMaterialRemainLosslessAfterRestart() {
        Fixture fixture = fixture(store(), "conflict");
        AdjudicationSnapshot opened = fixture.service.openWork(USER, "org-v0", "V0-SYN-ADJ-CASE-001", "v1",
                List.of("V0-SYN-ADJ-MAT-002"), "open-conflict");
        AdjudicationSnapshot inspected = fixture.service.inspectMaterials(USER, opened.work().workItemId(), "inspect-conflict");
        fixture.service.matchLegalCandidates(USER, opened.work().workItemId(), 1, "match-conflict");
        fixture.service.createDraft(USER, opened.work().workItemId(), 1, "draft-conflict");
        AdjudicationSnapshot recovered = store().findByWorkItemId(opened.work().workItemId()).orElseThrow().snapshot();
        assertEquals(inspected.inspections().getFirst().missingItems(), recovered.inspections().getFirst().missingItems());
        assertEquals(inspected.inspections().getFirst().conflicts(), recovered.inspections().getFirst().conflicts());
        assertEquals(List.of("专利比对材料"), recovered.drafts().getFirst().missingItems());
        assertEquals(List.of("发生日期存在两种记载"), recovered.drafts().getFirst().conflicts());
    }

    private boolean saveAfter(CountDownLatch start, Dm8AdjudicationStore repository, String operation, String key,
            long expectedVersion, AdjudicationSnapshot snapshot) throws Exception {
        start.await();
        try {
            repository.save(USER, operation, key, operation, expectedVersion, snapshot);
            return true;
        } catch (AdjudicationStore.VersionConflict expected) {
            return false;
        }
    }

    private static AdjudicationSnapshot appendInspection(AdjudicationSnapshot base, String suffix) {
        MaterialInspection inspection = new MaterialInspection(uuid("inspection-" + suffix), 1, base.work().materialRefs(),
                List.of("statement-" + suffix), List.of(), List.of(), List.of(), null, Instant.parse("2026-09-16T06:00:00Z"));
        return new AdjudicationSnapshot(base.work(), List.of(inspection), base.candidates(), base.citations(), base.drafts(),
                base.reviews(), base.reviewImpacts());
    }

    private Fixture fixture(Dm8AdjudicationStore repository, String prefix) {
        SyntheticAdjudicationSourceAdapter sources = new SyntheticAdjudicationSourceAdapter();
        AtomicInteger sequence = new AtomicInteger();
        AdjudicationApplicationService service = new AdjudicationApplicationService(repository,
                new V0AdjudicationAuthorizationAdapter(), sources,
                Clock.fixed(Instant.parse("2026-09-16T06:00:00Z"), ZoneOffset.UTC),
                () -> uuid(prefix + "-" + sequence.incrementAndGet()));
        return new Fixture(service, sources);
    }

    private AdjudicationApplicationService service(Dm8AdjudicationStore repository, String prefix) {
        return fixture(repository, prefix).service;
    }

    private Dm8AdjudicationStore store() {
        return new Dm8AdjudicationStore(() -> {
            try { return connection(); } catch (SQLException exception) { throw new IllegalStateException(exception); }
        });
    }

    private Connection connection() throws SQLException { return DriverManager.getConnection(url, user, password); }

    private int tableCount(String table) {
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next(); return r.getInt(1);
        } catch (SQLException exception) { throw new IllegalStateException(exception); }
    }

    private int rowCount(String table, String columnA, String valueA, String columnB, String valueB) {
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE " + columnA + "=? AND " + columnB + "=?")) {
            s.setString(1, valueA); s.setString(2, valueB); try (ResultSet r = s.executeQuery()) { r.next(); return r.getInt(1); }
        } catch (SQLException exception) { throw new IllegalStateException(exception); }
    }

    private static String uuid(String value) { return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }

    private static void execute(Connection connection, String file) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)")) if (!sql.isBlank()) statement.execute(sql);
        }
    }

    private static void executeIgnoring(Connection connection, String file) throws Exception {
        try { execute(connection, file); } catch (SQLException ignored) { }
    }

    private record Fixture(AdjudicationApplicationService service, SyntheticAdjudicationSourceAdapter sources) { }
}
