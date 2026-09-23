package cn.xinji.agentplatform.adjudication.application;

import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

public final class AdjudicationApplicationService {
    public static final String SYNTHETIC_SCOPE = "SYNTHETIC_V0";
    private final AdjudicationStore store; private final AuthorizationPort authorization; private final SourcePort sources;
    private final Clock clock; private final Supplier<String> ids;
    public AdjudicationApplicationService(AdjudicationStore store, AuthorizationPort authorization, SourcePort sources) {
        this(store, authorization, sources, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }
    public AdjudicationApplicationService(AdjudicationStore store, AuthorizationPort authorization, SourcePort sources, Clock clock, Supplier<String> ids) {
        this.store = store; this.authorization = authorization; this.sources = sources; this.clock = clock; this.ids = ids;
    }

    public AdjudicationSnapshot openWork(String actor, String organization, String caseId, String caseVersion, List<String> materialIds, String key) {
        String fp = fingerprint("open", organization, caseId, caseVersion, materialIds);
        Optional<AdjudicationSnapshot> replay = replay(actor, "OPEN", key, fp); if (replay.isPresent()) return replay.get();
        requireAccess(actor, "READ_CASE", caseId);
        Source caseRef = sources.findCase(caseId, caseVersion).orElseThrow(() -> error("SOURCE_NOT_FOUND"));
        List<Source> materials = new ArrayList<>();
        for (String materialId : materialIds) { requireAccess(actor, "READ_MATERIAL", materialId); materials.add(sources.findMaterial(materialId).orElseThrow(() -> error("SOURCE_NOT_FOUND"))); }
        return persist(actor, "OPEN", key, fp, 0, AdjudicationSnapshot.open(new Work(ids.get(), caseRef, List.copyOf(materials), organization, now(), SYNTHETIC_SCOPE)));
    }
    public AdjudicationSnapshot inspectMaterials(String actor, String workId, String key) {
        String fp = fingerprint("inspect", workId); Optional<AdjudicationSnapshot> replay = replay(actor, "INSPECT", key, fp); if (replay.isPresent()) return replay.get();
        AdjudicationStore.StoredWork stored = owned(actor, workId); AdjudicationSnapshot current = stored.snapshot();
        List<String> statements = new ArrayList<>(), unresolved = new ArrayList<>(), missing = new ArrayList<>(), conflicts = new ArrayList<>();
        for (Source material : current.work().materialRefs()) {
            requireAccess(actor, "READ_MATERIAL", material.id()); MaterialData data = sources.materialData(material.id()).orElseThrow(() -> error("SOURCE_UNAVAILABLE"));
            if (!data.available()) { missing.add("SOURCE_UNAVAILABLE:" + material.id()); continue; }
            statements.addAll(data.partyStatements()); unresolved.addAll(data.unresolvedFacts()); missing.addAll(data.missingItems()); conflicts.addAll(data.conflicts());
        }
        MaterialInspection inspection = new MaterialInspection(ids.get(), current.inspections().size() + 1, current.work().materialRefs(), List.copyOf(statements), List.copyOf(unresolved), List.copyOf(missing), List.copyOf(conflicts), latestInspection(current), now());
        return persist(actor, "INSPECT", key, fp, stored.persistenceVersion(), new AdjudicationSnapshot(current.work(), append(current.inspections(), inspection), current.candidates(), current.citations(), current.drafts(), current.reviews(), current.reviewImpacts()));
    }
    public AdjudicationSnapshot matchLegalCandidates(String actor, String workId, int inspectionVersion, String key) {
        String fp = fingerprint("match", workId, inspectionVersion); Optional<AdjudicationSnapshot> replay = replay(actor, "MATCH", key, fp); if (replay.isPresent()) return replay.get();
        AdjudicationStore.StoredWork stored = owned(actor, workId); AdjudicationSnapshot current = stored.snapshot(); MaterialInspection inspection = latest(current.inspections(), "NOT_FOUND"); if (inspection.version() != inspectionVersion) throw error("VERSION_CONFLICT");
        List<LegalCandidate> entries = sources.legalCandidates(inspection).stream().map(v -> new LegalCandidate(v.code(), v.source(), v.location(), v.doubts())).toList();
        List<String> limitations = new ArrayList<>(); if (!inspection.missingItems().isEmpty()) limitations.add("MATERIALS_INSUFFICIENT"); if (!inspection.conflicts().isEmpty()) limitations.add("MATERIAL_CONFLICT");
        LegalCandidateSet set = new LegalCandidateSet(ids.get(), current.candidates().size() + 1, ref(inspection), entries, List.copyOf(limitations), latestCandidate(current), now());
        return persist(actor, "MATCH", key, fp, stored.persistenceVersion(), new AdjudicationSnapshot(current.work(), current.inspections(), append(current.candidates(), set), current.citations(), current.drafts(), current.reviews(), current.reviewImpacts()));
    }
    public AdjudicationSnapshot cite(String actor, String workId, int expectedVersion, String code, String action, String reason, String key) {
        String fp = fingerprint("cite", workId, expectedVersion, code, action, reason); Optional<AdjudicationSnapshot> replay = replay(actor, "CITE", key, fp); if (replay.isPresent()) return replay.get(); requireHuman(actor); if (reason == null || reason.isBlank()) throw error("REASON_REQUIRED");
        AdjudicationStore.StoredWork stored = owned(actor, workId); AdjudicationSnapshot current = stored.snapshot(); int actual = current.citations().size(); if (actual != expectedVersion) throw error("VERSION_CONFLICT");
        if (!Set.of("SELECT", "REVISE", "REMOVE").contains(action)) throw error("INVALID_ACTION"); LegalCandidate candidate = latest(current.candidates(), "NOT_FOUND").entries().stream().filter(v -> v.code().equals(code)).findFirst().orElseThrow(() -> error("NOT_FOUND"));
        Citation citation = new Citation(ids.get(), actual + 1, candidate.source(), candidate.location(), reason, action, latestCitation(current), now());
        return persist(actor, "CITE", key, fp, stored.persistenceVersion(), new AdjudicationSnapshot(current.work(), current.inspections(), current.candidates(), append(current.citations(), citation), current.drafts(), current.reviews(), current.reviewImpacts()));
    }
    public AdjudicationSnapshot createDraft(String actor, String workId, int inspectionVersion, String key) {
        String fp = fingerprint("draft", workId, inspectionVersion); Optional<AdjudicationSnapshot> replay = replay(actor, "DRAFT", key, fp); if (replay.isPresent()) return replay.get();
        AdjudicationStore.StoredWork stored = owned(actor, workId); AdjudicationSnapshot current = stored.snapshot(); MaterialInspection inspection = latest(current.inspections(), "NOT_FOUND"); if (inspection.version() != inspectionVersion) throw error("VERSION_CONFLICT");
        for (Source source : current.work().materialRefs()) if (!sources.materialData(source.id()).orElseThrow(() -> error("SOURCE_UNAVAILABLE")).available()) throw error("SOURCE_UNAVAILABLE");
        Source template = sources.template(); List<String> citationRefs = current.citations().stream().map(AdjudicationApplicationService::ref).toList();
        Draft draft = new Draft(ids.get(), current.drafts().size() + 1, ref(inspection), citationRefs, template, sources.draftSections(inspection, current.citations()), inspection.missingItems(), inspection.conflicts(), latestDraft(current), now(), true);
        return persist(actor, "DRAFT", key, fp, stored.persistenceVersion(), new AdjudicationSnapshot(current.work(), current.inspections(), current.candidates(), current.citations(), append(current.drafts(), draft), current.reviews(), current.reviewImpacts()));
    }
    public AdjudicationSnapshot reviseDraft(String actor, String workId, int expectedVersion, String replacementText, String reason, String key) {
        String fp = fingerprint("revise-draft", workId, expectedVersion, replacementText, reason); Optional<AdjudicationSnapshot> replay = replay(actor, "REVISE_DRAFT", key, fp); if (replay.isPresent()) return replay.get();
        requireHuman(actor); if (reason == null || reason.isBlank() || replacementText == null || replacementText.isBlank()) throw error("REASON_REQUIRED");
        AdjudicationStore.StoredWork stored = owned(actor, workId); AdjudicationSnapshot current = stored.snapshot(); Draft previous = latest(current.drafts(), "NOT_FOUND"); if (previous.version() != expectedVersion) throw error("VERSION_CONFLICT");
        List<Section> sections = new ArrayList<>(previous.sections()); Section first = sections.getFirst(); sections.set(0, new Section(first.anchor(), replacementText, first.sources()));
        Draft revised = new Draft(ids.get(), previous.version() + 1, previous.inspectionRef(), previous.citationRefs(), previous.templateRef(), List.copyOf(sections), previous.missingItems(), previous.conflicts(), previous.draftId() + "@" + previous.version(), now(), true);
        return persist(actor, "REVISE_DRAFT", key, fp, stored.persistenceVersion(), new AdjudicationSnapshot(current.work(), current.inspections(), current.candidates(), current.citations(), append(current.drafts(), revised), current.reviews(), current.reviewImpacts()));
    }
    public AdjudicationSnapshot reviewDraft(String actor, String workId, int expectedDraftVersion, String action, String reason, String key) {
        String fp = fingerprint("review", workId, expectedDraftVersion, action, reason); Optional<AdjudicationSnapshot> replay = replay(actor, "REVIEW", key, fp); if (replay.isPresent()) return replay.get(); requireHuman(actor); if (reason == null || reason.isBlank()) throw error("REASON_REQUIRED");
        AdjudicationStore.StoredWork stored = owned(actor, workId); AdjudicationSnapshot current = stored.snapshot(); Draft draft = latest(current.drafts(), "NOT_FOUND"); if (draft.version() != expectedDraftVersion) throw error("VERSION_CONFLICT"); if (!Set.of("CONFIRM", "RETURN").contains(action)) throw error("INVALID_ACTION");
        if ("CONFIRM".equals(action) && (!draft.missingItems().isEmpty() || !draft.conflicts().isEmpty())) throw error("MATERIALS_INSUFFICIENT");
        TestReview review = new TestReview(ids.get(), draft.draftId(), draft.version(), action, reason, actor, now(), "SYNTHETIC_PROCESS_TEST", true);
        return persist(actor, "REVIEW", key, fp, stored.persistenceVersion(), new AdjudicationSnapshot(current.work(), current.inspections(), current.candidates(), current.citations(), current.drafts(), append(current.reviews(), review), current.reviewImpacts()));
    }
    public AdjudicationSnapshot read(String actor, String workId) { AdjudicationSnapshot snapshot = owned(actor, workId).snapshot(); requireAccess(actor, "READ_CASE", snapshot.work().caseRef().id()); for (Source value : snapshot.work().materialRefs()) requireAccess(actor, "READ_MATERIAL", value.id()); return snapshot; }
    public Object readVersion(String actor, String workId, String resource, String resourceId, int version) {
        AdjudicationSnapshot snapshot = read(actor, workId);
        return switch (resource) {
            case "material-inspections" -> snapshot.inspections().stream().filter(value -> value.inspectionId().equals(resourceId) && value.version() == version).findFirst().orElseThrow(() -> error("NOT_FOUND"));
            case "legal-candidate-sets" -> snapshot.candidates().stream().filter(value -> value.candidateSetId().equals(resourceId) && value.version() == version).findFirst().orElseThrow(() -> error("NOT_FOUND"));
            case "citations" -> snapshot.citations().stream().filter(value -> value.citationId().equals(resourceId) && value.version() == version).findFirst().orElseThrow(() -> error("NOT_FOUND"));
            case "drafts" -> snapshot.drafts().stream().filter(value -> value.draftId().equals(resourceId) && value.version() == version).findFirst().orElseThrow(() -> error("NOT_FOUND"));
            case "test-reviews" -> snapshot.reviews().stream().filter(value -> value.reviewId().equals(resourceId)).findFirst().orElseThrow(() -> error("NOT_FOUND"));
            default -> throw error("INVALID_INPUT");
        };
    }
    private AdjudicationStore.StoredWork owned(String actor, String workId) { AdjudicationStore.StoredWork found = store.findByWorkItemId(workId).orElseThrow(() -> error("NOT_FOUND")); if (!found.ownerRef().equals(actor) && !authorization.isTrustedHuman(actor)) throw error("UNAUTHORIZED"); return found; }
    private void requireAccess(String actor, String action, String ref) { if (!authorization.mayAccess(actor, action, ref)) throw error("UNAUTHORIZED"); }
    private void requireHuman(String actor) { if (!authorization.isTrustedHuman(actor)) throw error("UNAUTHORIZED"); }
    private Optional<AdjudicationSnapshot> replay(String actor, String op, String key, String fp) { Optional<AdjudicationStore.StoredResult> value = store.findByIdempotency(actor, op, key); if (value.isEmpty()) return Optional.empty(); if (!value.get().fingerprint().equals(fp)) throw error("IDEMPOTENCY_CONFLICT"); return Optional.of(value.get().snapshot()); }
    private AdjudicationSnapshot persist(String actor, String op, String key, String fp, long expectedVersion, AdjudicationSnapshot value) { try { return store.save(actor, op, key, fp, expectedVersion, value); } catch (AdjudicationStore.IdempotencyConflict ex) { throw error("IDEMPOTENCY_CONFLICT"); } catch (AdjudicationStore.VersionConflict ex) { throw error("VERSION_CONFLICT"); } }
    private static <T> T latest(List<T> values, String code) { if (values.isEmpty()) throw error(code); return values.get(values.size() - 1); }
    private static <T> List<T> append(List<T> prior, T next) { List<T> values = new ArrayList<>(prior); values.add(next); return List.copyOf(values); }
    private static String fingerprint(Object... values) { return Arrays.deepToString(values); }
    private static String ref(MaterialInspection value) { return value.inspectionId() + "@" + value.version(); } private static String ref(Citation value) { return value.citationId() + "@" + value.version(); }
    private static String latestInspection(AdjudicationSnapshot value) { return value.inspections().isEmpty() ? null : ref(value.inspections().get(value.inspections().size() - 1)); } private static String latestCandidate(AdjudicationSnapshot value) { return value.candidates().isEmpty() ? null : value.candidates().get(value.candidates().size() - 1).candidateSetId() + "@" + value.candidates().get(value.candidates().size() - 1).version(); } private static String latestCitation(AdjudicationSnapshot value) { return value.citations().isEmpty() ? null : ref(value.citations().get(value.citations().size() - 1)); } private static String latestDraft(AdjudicationSnapshot value) { return value.drafts().isEmpty() ? null : value.drafts().get(value.drafts().size() - 1).draftId() + "@" + value.drafts().get(value.drafts().size() - 1).version(); }
    private Instant now() { return clock.instant(); } private static AdjudicationException error(String code) { return new AdjudicationException(code); }
    public interface AuthorizationPort { boolean mayAccess(String actorRef, String action, String resourceId); boolean isTrustedHuman(String actorRef); }
    public interface SourcePort {
        Optional<Source> findCase(String id, String version); Optional<Source> findMaterial(String id); Optional<MaterialData> materialData(String id); List<LegalData> legalCandidates(MaterialInspection inspection); Source template(); List<Section> draftSections(MaterialInspection inspection, List<Citation> citations);
        default List<Source> cases() { return List.of(); }
        default List<MaterialCatalogItem> materialItems() { return List.of(); }
        default String dataMode() { return "LOCAL_SOURCE_UNAVAILABLE"; }
        default String catalogVersion() { return "unconfigured"; }
        default String limitation() { return "本地裁决来源目录未配置。"; }
        record MaterialCatalogItem(Source source, Source caseRef, MaterialData data) {}
    }
    public record MaterialData(boolean available, List<String> partyStatements, List<String> unresolvedFacts, List<String> missingItems, List<String> conflicts) {} public record LegalData(String code, Source source, String location, List<String> doubts) {}
    public static final class AdjudicationException extends RuntimeException { private final String code; public AdjudicationException(String code) { super(code); this.code = code; } public String code() { return code; } }
}
