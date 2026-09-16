package cn.xinji.agentplatform.adjudication;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.adjudication.adapter.out.memory.InMemoryAdjudicationStore;
import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.SyntheticAdjudicationSourceAdapter;
import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.V0AdjudicationAuthorizationAdapter;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.AdjudicationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdjudicationApplicationServiceTest {
    private static final String USER = "v0-adjudication-user";
    private static final String REVIEWER = "v0-adjudication-reviewer";

    @Test void normalSyntheticFlowRetainsSourcesVersionsAndNonOfficialReview() {
        Fixture f = fixture(); var opened = f.service.openWork(USER, "org-v0", "V0-SYN-ADJ-CASE-001", "v1", List.of("V0-SYN-ADJ-MAT-001"), "open");
        var inspected = f.service.inspectMaterials(USER, opened.work().workItemId(), "inspect"); var matched = f.service.matchLegalCandidates(USER, opened.work().workItemId(), 1, "match");
        var cited = f.service.cite(REVIEWER, opened.work().workItemId(), 0, "V0-SYN-ADJ-LAW-001", "SELECT", "test selection", "cite");
        var drafted = f.service.createDraft(USER, opened.work().workItemId(), 1, "draft"); var reviewed = f.service.reviewDraft(REVIEWER, opened.work().workItemId(), 1, "CONFIRM", "test review", "review");
        assertEquals(1, inspected.inspections().size()); assertEquals(1, matched.candidates().size()); assertEquals(1, cited.citations().size()); assertEquals(2, drafted.drafts().get(0).sections().size());
        assertTrue(reviewed.reviews().get(0).notOfficialDecision()); assertEquals("SYNTHETIC_PROCESS_TEST", reviewed.reviews().get(0).scopeLabel()); assertEquals("SYNTHETIC_V0", opened.work().scopeLabel());
    }
    @Test void materialsConflictAllowsDraftButBlocksConfirmation() {
        Fixture f = fixture(); var work = f.service.openWork(USER, "org", "V0-SYN-ADJ-CASE-001", "v1", List.of("V0-SYN-ADJ-MAT-002"), "open-conflict");
        f.service.inspectMaterials(USER, work.work().workItemId(), "inspect-conflict"); f.service.matchLegalCandidates(USER, work.work().workItemId(), 1, "match-conflict"); f.service.createDraft(USER, work.work().workItemId(), 1, "draft-conflict");
        assertEquals("MATERIALS_INSUFFICIENT", assertThrows(AdjudicationException.class, () -> f.service.reviewDraft(REVIEWER, work.work().workItemId(), 1, "CONFIRM", "cannot confirm", "review-conflict")).code());
    }
    @Test void unauthorizedActorCannotOpenWork() {
        Fixture f = fixture(); assertEquals("UNAUTHORIZED", assertThrows(AdjudicationException.class, () -> f.service.openWork("unknown", "org", "V0-SYN-ADJ-CASE-001", "v1", List.of("V0-SYN-ADJ-MAT-001"), "open-denied")).code());
    }
    @Test void sameIdempotencyKeyReplaysAndDifferentInputConflicts() {
        Fixture f = fixture(); var first = f.service.openWork(USER, "org", "V0-SYN-ADJ-CASE-001", "v1", List.of("V0-SYN-ADJ-MAT-001"), "idem");
        assertEquals(first.work().workItemId(), f.service.openWork(USER, "org", "V0-SYN-ADJ-CASE-001", "v1", List.of("V0-SYN-ADJ-MAT-001"), "idem").work().workItemId());
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(AdjudicationException.class, () -> f.service.openWork(USER, "other", "V0-SYN-ADJ-CASE-001", "v1", List.of("V0-SYN-ADJ-MAT-001"), "idem")).code());
    }
    @Test void staleCitationVersionAndUnavailableSourceAreRejected() {
        Fixture f = fixture(); var work = f.service.openWork(USER, "org", "V0-SYN-ADJ-CASE-001", "v1", List.of("V0-SYN-ADJ-MAT-001"), "open-v");
        f.service.inspectMaterials(USER, work.work().workItemId(), "inspect-v"); f.service.matchLegalCandidates(USER, work.work().workItemId(), 1, "match-v"); f.service.cite(REVIEWER, work.work().workItemId(), 0, "V0-SYN-ADJ-LAW-001", "SELECT", "first", "cite-v");
        assertEquals("VERSION_CONFLICT", assertThrows(AdjudicationException.class, () -> f.service.cite(REVIEWER, work.work().workItemId(), 0, "V0-SYN-ADJ-LAW-001", "SELECT", "stale", "cite-stale")).code());
        f.sources.markUnavailable("V0-SYN-ADJ-MAT-001"); assertEquals("SOURCE_UNAVAILABLE", assertThrows(AdjudicationException.class, () -> f.service.createDraft(USER, work.work().workItemId(), 1, "draft-unavailable")).code());
    }
    @Test void draftRevisionAppendsHistoryWithoutOverwritingOriginal() {
        Fixture f = fixture(); var work = preparedDraft(f, "revision"); var revised = f.service.reviseDraft(REVIEWER, work.work().workItemId(), 1, "人工合成修订内容", "test revision", "revise-draft");
        assertEquals(2, revised.drafts().size()); assertTrue(revised.drafts().get(0).sections().get(0).content().startsWith("合成草稿")); assertEquals("人工合成修订内容", revised.drafts().get(1).sections().get(0).content()); assertNotNull(revised.drafts().get(1).previousRef());
    }
    @Test void staleDraftRevisionDoesNotOverwriteCurrentVersion() {
        Fixture f = fixture(); var work = preparedDraft(f, "stale-draft"); f.service.reviseDraft(REVIEWER, work.work().workItemId(), 1, "first revision", "reason", "revise-one");
        assertEquals("VERSION_CONFLICT", assertThrows(AdjudicationException.class, () -> f.service.reviseDraft(REVIEWER, work.work().workItemId(), 1, "stale revision", "reason", "revise-two")).code());
    }
    @Test void onlyTrustedHumanCanReviewAndReturnRequiresReason() {
        Fixture f = fixture(); var work = preparedDraft(f, "review-boundary");
        assertEquals("UNAUTHORIZED", assertThrows(AdjudicationException.class, () -> f.service.reviewDraft(USER, work.work().workItemId(), 1, "CONFIRM", "not allowed", "review-user")).code());
        assertEquals("REASON_REQUIRED", assertThrows(AdjudicationException.class, () -> f.service.reviewDraft(REVIEWER, work.work().workItemId(), 1, "RETURN", "", "review-empty")).code());
        assertEquals("INVALID_ACTION", assertThrows(AdjudicationException.class, () -> f.service.reviewDraft(REVIEWER, work.work().workItemId(), 1, "SIGN", "no", "review-sign")).code());
    }
    @Test void exactHistoricalVersionCanBeReadButUnsupportedResourceIsRejected() {
        Fixture f = fixture(); var work = preparedDraft(f, "history"); var revised = f.service.reviseDraft(REVIEWER, work.work().workItemId(), 1, "new", "reason", "history-revise");
        var original = f.service.readVersion(USER, work.work().workItemId(), "drafts", revised.drafts().get(0).draftId(), 1); assertEquals(1, ((cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Draft) original).version());
        assertEquals("INVALID_INPUT", assertThrows(AdjudicationException.class, () -> f.service.readVersion(USER, work.work().workItemId(), "official-decisions", "none", 1)).code());
    }
    @Test void invalidCitationActionAndMissingCandidateRemainDistinctErrors() {
        Fixture f = fixture(); var work = f.service.openWork(USER, "org", "V0-SYN-ADJ-CASE-001", "v1", List.of("V0-SYN-ADJ-MAT-001"), "open-errors"); f.service.inspectMaterials(USER, work.work().workItemId(), "inspect-errors"); f.service.matchLegalCandidates(USER, work.work().workItemId(), 1, "match-errors");
        assertEquals("INVALID_ACTION", assertThrows(AdjudicationException.class, () -> f.service.cite(REVIEWER, work.work().workItemId(), 0, "V0-SYN-ADJ-LAW-001", "CONFIRM", "reason", "invalid-action")).code());
        assertEquals("NOT_FOUND", assertThrows(AdjudicationException.class, () -> f.service.cite(REVIEWER, work.work().workItemId(), 0, "V0-SYN-ADJ-LAW-MISSING", "SELECT", "reason", "missing-candidate")).code());
    }
    @Test void missingSourceAndInspectionVersionConflictRemainDistinct() {
        Fixture f = fixture(); assertEquals("SOURCE_NOT_FOUND", assertThrows(AdjudicationException.class, () -> f.service.openWork(USER, "org", "V0-SYN-ADJ-CASE-001", "v1", List.of("V0-SYN-ADJ-MAT-MISSING"), "missing-material")).code());
        var work = f.service.openWork(USER, "org", "V0-SYN-ADJ-CASE-001", "v1", List.of("V0-SYN-ADJ-MAT-001"), "open-version"); f.service.inspectMaterials(USER, work.work().workItemId(), "inspect-version");
        assertEquals("VERSION_CONFLICT", assertThrows(AdjudicationException.class, () -> f.service.matchLegalCandidates(USER, work.work().workItemId(), 2, "match-stale")).code());
    }
    private cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot preparedDraft(Fixture f, String suffix) { var work = f.service.openWork(USER, "org", "V0-SYN-ADJ-CASE-001", "v1", List.of("V0-SYN-ADJ-MAT-001"), "open-" + suffix); f.service.inspectMaterials(USER, work.work().workItemId(), "inspect-" + suffix); f.service.matchLegalCandidates(USER, work.work().workItemId(), 1, "match-" + suffix); f.service.cite(REVIEWER, work.work().workItemId(), 0, "V0-SYN-ADJ-LAW-001", "SELECT", "reason", "cite-" + suffix); f.service.createDraft(USER, work.work().workItemId(), 1, "draft-" + suffix); return work; }
    private Fixture fixture() { InMemoryAdjudicationStore store = new InMemoryAdjudicationStore(); SyntheticAdjudicationSourceAdapter sources = new SyntheticAdjudicationSourceAdapter(); AtomicInteger sequence = new AtomicInteger(); return new Fixture(new AdjudicationApplicationService(store, new V0AdjudicationAuthorizationAdapter(), sources, Clock.fixed(Instant.parse("2026-09-16T06:00:00Z"), ZoneOffset.UTC), () -> "adj-id-" + sequence.incrementAndGet()), sources); }
    private record Fixture(AdjudicationApplicationService service, SyntheticAdjudicationSourceAdapter sources) {}
}
