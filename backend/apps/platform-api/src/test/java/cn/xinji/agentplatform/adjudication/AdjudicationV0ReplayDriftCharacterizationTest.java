package cn.xinji.agentplatform.adjudication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.xinji.agentplatform.adjudication.adapter.out.memory.InMemoryAdjudicationStore;
import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.SyntheticAdjudicationSourceAdapter;
import cn.xinji.agentplatform.adjudication.adapter.out.synthetic.V0AdjudicationAuthorizationAdapter;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.AdjudicationException;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.AuthorizationPort;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.LegalData;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.MaterialData;
import cn.xinji.agentplatform.adjudication.application.AdjudicationApplicationService.SourcePort;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Citation;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.MaterialInspection;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Section;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.Source;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdjudicationV0ReplayDriftCharacterizationTest {
    private static final String ACTOR = "v0-adjudication-user";
    private static final String CASE = "V0-SYN-ADJ-CASE-001";
    private static final String MATERIAL = "V0-SYN-ADJ-MAT-001";

    @Test void existingV0ReplayReturnsSnapshotAfterAccessRevocationAndSourceWithdrawal() {
        var store = new InMemoryAdjudicationStore();
        var sources = new SyntheticAdjudicationSourceAdapter();
        var access = new MutableAccess();
        var service = service(store, access, sources);
        var opened = service.openWork(ACTOR, "org-v0", CASE, "v1", List.of(MATERIAL), "open-key");
        String workId = opened.work().workItemId();
        var inspected = service.inspectMaterials(ACTOR, workId, "inspect-key");

        access.allowed = false;
        sources.markUnavailable(MATERIAL);
        assertSame(opened, service.openWork(ACTOR, "org-v0", CASE, "v1", List.of(MATERIAL), "open-key"));
        assertSame(inspected, service.inspectMaterials(ACTOR, workId, "inspect-key"));
        assertEquals("UNAUTHORIZED", assertThrows(AdjudicationException.class,
                () -> service.read(ACTOR, workId)).code());
        assertEquals("UNAUTHORIZED", assertThrows(AdjudicationException.class,
                () -> service.inspectMaterials(ACTOR, workId, "new-inspect-key")).code());
        assertEquals(2, store.findByWorkItemId(workId).orElseThrow().persistenceVersion());
        assertEquals(1, store.findByWorkItemId(workId).orElseThrow().snapshot().inspections().size());
    }

    @Test void existingV0InspectionCanPairPinnedV1RefWithLaterMaterialContent() {
        var store = new InMemoryAdjudicationStore();
        var sources = new ChangingMaterialSource();
        var service = service(store, new MutableAccess(), sources);
        var opened = service.openWork(ACTOR, "org-v0", CASE, "v1", List.of(MATERIAL), "open-key");
        assertEquals("v1", opened.work().materialRefs().getFirst().version());

        sources.current = new MaterialData(true, List.of("later synthetic content"), List.of(), List.of(), List.of());
        var inspected = service.inspectMaterials(ACTOR, opened.work().workItemId(), "inspect-key");
        assertEquals("v1", inspected.inspections().getFirst().inputs().getFirst().version());
        assertEquals(List.of("later synthetic content"), inspected.inspections().getFirst().partyStatements());
        assertEquals(2, store.findByWorkItemId(opened.work().workItemId()).orElseThrow().persistenceVersion());
    }

    @Test void existingV0WorkCanAttachMaterialCatalogedUnderAnotherCase() {
        var store = new InMemoryAdjudicationStore();
        var sources = new SyntheticAdjudicationSourceAdapter();
        String otherCaseMaterial = "V0-SYN-ADJ-MAT-005";
        assertEquals(CASE, sources.materialItem("V0-SYN-ADJ-MAT-004").orElseThrow().caseRef().id());
        assertEquals("V0-SYN-ADJ-CASE-002",
                sources.materialItem(otherCaseMaterial).orElseThrow().caseRef().id());

        var service = service(store, new MutableAccess(), sources);
        var opened = service.openWork(ACTOR, "org-v0", CASE, "v1", List.of(otherCaseMaterial), "cross-case-open");
        var inspected = service.inspectMaterials(ACTOR, opened.work().workItemId(), "cross-case-inspect");
        assertEquals(CASE, inspected.work().caseRef().id());
        assertEquals(otherCaseMaterial, inspected.work().materialRefs().getFirst().id());
        assertEquals(sources.materialData(otherCaseMaterial).orElseThrow().partyStatements(),
                inspected.inspections().getFirst().partyStatements());
        assertEquals(2, store.findByWorkItemId(opened.work().workItemId()).orElseThrow().persistenceVersion());
    }

    @Test void existingV0ReviewerCanCreateDraftAndConfirmOwnDraftAsSyntheticTest() {
        var store = new InMemoryAdjudicationStore();
        var service = service(store, new V0AdjudicationAuthorizationAdapter(),
                new SyntheticAdjudicationSourceAdapter());
        String reviewer = "v0-adjudication-reviewer";
        var opened = service.openWork(reviewer, "org-v0", CASE, "v1", List.of(MATERIAL), "reviewer-open");
        String workId = opened.work().workItemId();
        service.inspectMaterials(reviewer, workId, "reviewer-inspect");
        var drafted = service.createDraft(reviewer, workId, 1, "reviewer-draft");
        var reviewed = service.reviewDraft(reviewer, workId, 1, "CONFIRM", "synthetic check", "reviewer-review");

        assertEquals(1, drafted.drafts().getFirst().version());
        assertEquals(drafted.drafts().getFirst().draftId(), reviewed.reviews().getFirst().draftId());
        assertEquals(reviewer, reviewed.reviews().getFirst().actorRef());
        assertEquals("SYNTHETIC_PROCESS_TEST", reviewed.reviews().getFirst().scopeLabel());
        assertTrue(reviewed.reviews().getFirst().notOfficialDecision());
        assertEquals(4, store.findByWorkItemId(workId).orElseThrow().persistenceVersion());
    }

    private static AdjudicationApplicationService service(InMemoryAdjudicationStore store,
                                                           AuthorizationPort access, SourcePort sources) {
        var sequence = new AtomicInteger();
        return new AdjudicationApplicationService(store, access, sources,
                Clock.fixed(Instant.parse("2026-09-16T06:00:00Z"), ZoneOffset.UTC),
                () -> "v0-characterization-" + sequence.incrementAndGet());
    }

    private static final class MutableAccess implements AuthorizationPort {
        private boolean allowed = true;

        @Override public boolean mayAccess(String actor, String action, String resource) {
            return allowed && ACTOR.equals(actor);
        }

        @Override public boolean isTrustedHuman(String actor) { return false; }
    }

    private static final class ChangingMaterialSource implements SourcePort {
        private final SyntheticAdjudicationSourceAdapter delegate = new SyntheticAdjudicationSourceAdapter();
        private MaterialData current = delegate.materialData(MATERIAL).orElseThrow();

        @Override public Optional<Source> findCase(String id, String version) {
            return delegate.findCase(id, version);
        }

        @Override public Optional<Source> findMaterial(String id) { return delegate.findMaterial(id); }

        @Override public Optional<MaterialData> materialData(String id) {
            return MATERIAL.equals(id) ? Optional.of(current) : Optional.empty();
        }

        @Override public List<LegalData> legalCandidates(MaterialInspection inspection) {
            return delegate.legalCandidates(inspection);
        }

        @Override public Source template() { return delegate.template(); }

        @Override public List<Section> draftSections(MaterialInspection inspection, List<Citation> citations) {
            return delegate.draftSections(inspection, citations);
        }
    }
}
