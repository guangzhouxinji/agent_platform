package cn.xinji.agentplatform.enforcement;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.enforcement.adapter.out.memory.InMemoryEnforcementStore;
import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.SyntheticEnforcementSourceAdapter;
import cn.xinji.agentplatform.enforcement.adapter.out.synthetic.V0EnforcementAuthorizationAdapter;
import cn.xinji.agentplatform.enforcement.application.EnforcementApplicationService;
import cn.xinji.agentplatform.enforcement.application.EnforcementApplicationService.EnforcementException;
import cn.xinji.agentplatform.enforcement.domain.EnforcementSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EnforcementApplicationServiceTest {
    private static final String USER = "v0-enforcement-user";
    private static final String REVIEWER = "v0-enforcement-reviewer";

    @Test
    void t01NormalSyntheticFlowKeepsSourcesVersionsAndNonOfficialReview() {
        Fixture f = fixture();
        EnforcementSnapshot opened = f.service.openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "open-1");
        EnforcementSnapshot processed = f.service.process(USER, opened.workItem().workItemId(), List.of("V0-SYN-EVD-001", "V0-SYN-EVD-002"), "process-1");
        EnforcementSnapshot reviewed = f.service.reviewLatestAnalysis(REVIEWER, opened.workItem().workItemId(), 1, "CONFIRM", "synthetic flow checked", "review-1");

        assertEquals(3, processed.clueAnalyses().get(0).subjectCandidates().size());
        assertEquals(2, processed.evidenceReferences().size());
        assertEquals(2, processed.relationSets().get(0).relations().size());
        assertEquals("SUSPECTED_SUPPORT", processed.auxiliaryAnalyses().get(0).auxiliaryOutcome());
        assertTrue(processed.auxiliaryAnalyses().get(0).notOfficialDecision());
        assertEquals("SYNTHETIC_PROCESS_TEST", reviewed.reviews().get(0).scopeLabel());
        assertTrue(reviewed.reviews().get(0).notOfficialDecision());
    }

    @Test
    void t02MissingCreditCodeStaysEmptyAndRolesRemainSeparate() {
        Fixture f = fixture();
        var opened = f.service.openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "open-2");
        var result = f.service.process(USER, opened.workItem().workItemId(), List.of("V0-SYN-EVD-001"), "process-2");
        var subjects = result.clueAnalyses().get(0).subjectCandidates();
        assertEquals(List.of("投诉人", "经营者", "权利人"), subjects.stream().map(s -> s.roleAsStated()).toList());
        assertTrue(subjects.stream().allMatch(s -> s.creditCode() == null));
    }

    @Test
    void t03ConflictsUnknownCollectionAndUnsupportedMaterialStayExplicit() {
        Fixture f = fixture();
        var opened = f.service.openWork(USER, "org-v0", "V0-SYN-CLUE-002", "v1", "open-3");
        var result = f.service.process(USER, opened.workItem().workItemId(), List.of("V0-SYN-EVD-003", "V0-SYN-EVD-004", "V0-SYN-EVD-007"), "process-3");
        assertEquals(2, result.clueAnalyses().get(0).conflicts().get(0).candidates().size());
        assertTrue(result.evidenceReferences().stream().allMatch(e -> e.collector() == null && "NOT_PROVIDED".equals(e.collectionMissingReason())));
        assertTrue(result.relationSets().get(0).unprocessedItems().contains("V0-SYN-EVD-007:UNSUPPORTED_CONTENT"));
        assertEquals("MATERIALS_INSUFFICIENT", result.auxiliaryAnalyses().get(0).auxiliaryOutcome());
        assertNull(result.auxiliaryAnalyses().get(0).rightStatusRef());
    }

    @Test
    void t04UnauthorizedActorCannotOpenOrReadSourceData() {
        Fixture f = fixture();
        EnforcementException error = assertThrows(EnforcementException.class,
                () -> f.service.openWork("unknown", "org-v0", "V0-SYN-CLUE-001", "v1", "open-4"));
        assertEquals("UNAUTHORIZED", error.code());
        assertTrue(f.store.findByWorkItemId("V0-SYN-CLUE-001").isEmpty());
    }

    @Test
    void t05UnauthorizedEvidenceCannotEnterRelationsOrAnalysis() {
        Fixture f = fixture();
        var opened = f.service.openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "open-5");
        EnforcementException error = assertThrows(EnforcementException.class,
                () -> f.service.process(USER, opened.workItem().workItemId(), List.of("V0-SYN-EVD-005"), "process-5"));
        assertEquals("UNAUTHORIZED", error.code());
        assertTrue(f.store.findByWorkItemId(opened.workItem().workItemId()).orElseThrow().snapshot().evidenceReferences().isEmpty());
    }

    @Test
    void t06UnavailableSourceLeavesHistoryAndForcesNewResultToExcludeIt() {
        Fixture f = fixture();
        var opened = f.service.openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "open-6");
        var first = f.service.process(USER, opened.workItem().workItemId(), List.of("V0-SYN-EVD-006"), "process-6a");
        f.service.reviewLatestAnalysis(REVIEWER, opened.workItem().workItemId(), 1, "CONFIRM", "checked", "review-6");
        f.sources.markUnavailable("V0-SYN-EVD-006");
        var second = f.service.process(USER, opened.workItem().workItemId(), List.of("V0-SYN-EVD-006"), "process-6b");

        assertEquals(2, second.auxiliaryAnalyses().size());
        assertEquals(1, second.reviews().size());
        assertTrue(second.relationSets().get(1).relations().isEmpty());
        assertEquals("MATERIALS_INSUFFICIENT", second.auxiliaryAnalyses().get(1).auxiliaryOutcome());
        assertFalse(second.reviewImpacts().isEmpty());
        assertEquals("SUSPECTED_SUPPORT", first.auxiliaryAnalyses().get(0).auxiliaryOutcome());
    }

    @Test
    void t07SameIdempotencyKeyAndInputReturnsSameVersion() {
        Fixture f = fixture();
        var first = f.service.openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "idem-7");
        var replay = f.service.openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "idem-7");
        assertEquals(first.workItem().workItemId(), replay.workItem().workItemId());
    }

    @Test
    void t08SameIdempotencyKeyWithDifferentInputIsConflict() {
        Fixture f = fixture();
        f.service.openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "idem-8");
        EnforcementException error = assertThrows(EnforcementException.class,
                () -> f.service.openWork(USER, "another-org", "V0-SYN-CLUE-001", "v1", "idem-8"));
        assertEquals("IDEMPOTENCY_CONFLICT", error.code());
    }

    @Test
    void t09HumanRevisionAppendsVersionAndRejectsStaleExpectedVersion() {
        Fixture f = fixture();
        var opened = f.service.openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "open-9");
        f.service.process(USER, opened.workItem().workItemId(), List.of("V0-SYN-EVD-001"), "process-9");
        var revised = f.service.reviseClue(REVIEWER, opened.workItem().workItemId(), 1, "field checked", "revise-9a");
        assertEquals(2, revised.clueAnalyses().size());
        assertEquals("HUMAN_REVISION", revised.clueAnalyses().get(1).revisionKind());
        assertNotNull(revised.clueAnalyses().get(1).previousVersionRef());
        EnforcementException error = assertThrows(EnforcementException.class,
                () -> f.service.reviseClue(REVIEWER, opened.workItem().workItemId(), 1, "stale", "revise-9b"));
        assertEquals("VERSION_CONFLICT", error.code());
    }

    @Test
    void t10OnlyTrustedHumanReviewsAndNoOfficialDecisionSurfaceExists() {
        Fixture f = fixture();
        var opened = f.service.openWork(USER, "org-v0", "V0-SYN-CLUE-001", "v1", "open-10");
        f.service.process(USER, opened.workItem().workItemId(), List.of("V0-SYN-EVD-001"), "process-10");
        EnforcementException error = assertThrows(EnforcementException.class,
                () -> f.service.reviewLatestAnalysis(USER, opened.workItem().workItemId(), 1, "CONFIRM", "not allowed", "review-10"));
        assertEquals("UNAUTHORIZED", error.code());
        assertTrue(Arrays.stream(EnforcementApplicationService.class.getMethods())
                .map(method -> method.getName().toLowerCase())
                .noneMatch(name -> name.contains("official") || name.contains("penalty") || name.contains("illegal")));
    }

    @Test
    void t11MissingHistoricalRightStatusIsNotFilledFromCurrentState() {
        Fixture f = fixture();
        var opened = f.service.openWork(USER, "org-v0", "V0-SYN-CLUE-002", "v1", "open-11");
        var result = f.service.process(USER, opened.workItem().workItemId(), List.of("V0-SYN-EVD-003"), "process-11");
        var analysis = result.auxiliaryAnalyses().get(0);
        assertNull(analysis.rightStatusRef());
        assertTrue(analysis.gaps().contains("HISTORICAL_RIGHT_STATUS_MISSING"));
        assertEquals("MATERIALS_INSUFFICIENT", analysis.auxiliaryOutcome());
    }

    @Test
    void t12ErrorsAndPartialConditionsRemainDistinct() {
        Fixture f = fixture();
        assertEquals("UNAUTHORIZED", assertThrows(EnforcementException.class,
                () -> f.service.openWork("unknown", "org", "V0-SYN-CLUE-001", "v1", "e-unauthorized")).code());
        assertEquals("SOURCE_NOT_FOUND", assertThrows(EnforcementException.class,
                () -> f.service.openWork(USER, "org", "V0-SYN-MISSING", "v1", "e-missing")).code());
        var opened = f.service.openWork(USER, "org", "V0-SYN-CLUE-002", "v1", "e-open");
        var partial = f.service.process(USER, opened.workItem().workItemId(), List.of("V0-SYN-EVD-007"), "e-partial");
        assertEquals("MATERIALS_INSUFFICIENT", partial.auxiliaryAnalyses().get(0).auxiliaryOutcome());
        assertEquals(List.of("V0-SYN-EVD-007:UNSUPPORTED_CONTENT"), partial.relationSets().get(0).unprocessedItems());
        assertEquals("REASON_REQUIRED", assertThrows(EnforcementException.class,
                () -> f.service.reviewLatestAnalysis(REVIEWER, opened.workItem().workItemId(), 1, "RETURN", "", "e-review")).code());
    }

    private Fixture fixture() {
        InMemoryEnforcementStore store = new InMemoryEnforcementStore();
        V0EnforcementAuthorizationAdapter auth = new V0EnforcementAuthorizationAdapter();
        SyntheticEnforcementSourceAdapter sources = new SyntheticEnforcementSourceAdapter();
        AtomicInteger sequence = new AtomicInteger();
        EnforcementApplicationService service = new EnforcementApplicationService(
                store, auth, sources, Clock.fixed(Instant.parse("2026-09-15T08:00:00Z"), ZoneOffset.UTC),
                () -> "v0-id-" + sequence.incrementAndGet());
        return new Fixture(service, store, auth, sources);
    }

    private record Fixture(
            EnforcementApplicationService service,
            InMemoryEnforcementStore store,
            V0EnforcementAuthorizationAdapter auth,
            SyntheticEnforcementSourceAdapter sources) {}
}
