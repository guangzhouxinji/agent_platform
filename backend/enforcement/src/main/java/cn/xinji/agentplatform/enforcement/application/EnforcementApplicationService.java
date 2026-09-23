package cn.xinji.agentplatform.enforcement.application;

import cn.xinji.agentplatform.enforcement.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

public final class EnforcementApplicationService {
    public static final String SYNTHETIC_SCOPE = "SYNTHETIC_V0";
    private final EnforcementStore store;
    private final EnforcementAuthorizationPort authorization;
    private final EnforcementSourcePort sources;
    private final Clock clock;
    private final Supplier<String> ids;

    public EnforcementApplicationService(
            EnforcementStore store,
            EnforcementAuthorizationPort authorization,
            EnforcementSourcePort sources) {
        this(store, authorization, sources, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    public EnforcementApplicationService(
            EnforcementStore store,
            EnforcementAuthorizationPort authorization,
            EnforcementSourcePort sources,
            Clock clock,
            Supplier<String> ids) {
        this.store = store;
        this.authorization = authorization;
        this.sources = sources;
        this.clock = clock;
        this.ids = ids;
    }

    public EnforcementSnapshot openWork(
            String actorRef, String organizationRef, String clueId, String clueVersion, String idempotencyKey) {
        String fingerprint = fingerprint("open", organizationRef, clueId, clueVersion);
        String operation = "OPEN";
        Optional<EnforcementSnapshot> replay = replay(actorRef, operation, idempotencyKey, fingerprint);
        if (replay.isPresent()) return replay.get();
        requireAccess(actorRef, "READ_CLUE", clueId);
        EnforcementSourcePort.ClueData clue = sources.findClue(clueId, clueVersion)
                .orElseThrow(() -> error("SOURCE_NOT_FOUND"));
        EnforcementWorkItem item = new EnforcementWorkItem(ids.get(), clue.source(), organizationRef, now(), scope());
        EnforcementSnapshot snapshot = EnforcementSnapshot.open(item);
        return persist(actorRef, operation, idempotencyKey, fingerprint, snapshot);
    }

    public EnforcementSnapshot process(
            String actorRef, String workItemId, List<String> evidenceIds, String idempotencyKey) {
        String fingerprint = fingerprint("process", workItemId, String.join(",", evidenceIds));
        String operation = "PROCESS";
        Optional<EnforcementSnapshot> replay = replay(actorRef, operation, idempotencyKey, fingerprint);
        if (replay.isPresent()) return replay.get();
        EnforcementStore.StoredWork stored = owned(actorRef, workItemId);
        EnforcementSnapshot current = stored.snapshot();
        String workScope = current.workItem().scopeLabel();
        String clueId = current.workItem().clueRef().sourceId();
        requireAccess(actorRef, "READ_CLUE", clueId);
        EnforcementSourcePort.ClueData clue = sources.findClue(clueId, current.workItem().clueRef().version())
                .orElseThrow(() -> error("SOURCE_UNAVAILABLE"));

        int clueVersion = current.clueAnalyses().size() + 1;
        ClueAnalysisVersion clueAnalysis = new ClueAnalysisVersion(
                ids.get(), clueVersion, clue.source(), clue.subjects(), clue.facts(), clue.times(), clue.places(),
                clue.evidenceMentions(), clue.missingItems(), clue.conflicts(), "TO_BE_EVALUATED",
                latestClueRef(current), "MACHINE", "deterministic-analysis-adapter", now(), workScope);

        List<EvidenceReference> evidence = new ArrayList<>();
        List<String> unprocessed = new ArrayList<>();
        for (String evidenceId : evidenceIds) {
            requireAccess(actorRef, "READ_EVIDENCE", evidenceId);
            EnforcementSourcePort.EvidenceData data = sources.findEvidence(evidenceId)
                    .orElseThrow(() -> error("SOURCE_NOT_FOUND"));
            if (!clueId.equals(data.relatedClueId())) throw error("RELATION_NOT_FOUND");
            if (!"AVAILABLE".equals(data.availability())) unprocessed.add(evidenceId + ":" + data.availability());
            evidence.add(toEvidence(data, current.evidenceReferences(), evidence, workScope));
        }

        EvidenceRelationSetVersion relationSet = relationSet(current, clueAnalysis, evidence, unprocessed);
        AuxiliaryAnalysisVersion analysis = auxiliaryAnalysis(current, clueAnalysis, relationSet, evidence, clueId, workScope);
        List<String> impacts = new ArrayList<>(current.reviewImpacts());
        if (!unprocessed.isEmpty() && !current.auxiliaryAnalyses().isEmpty()) {
            impacts.add("SOURCE_CHANGE_REQUIRES_REVIEW:" + String.join(",", unprocessed));
        }
        EnforcementSnapshot next = new EnforcementSnapshot(
                current.workItem(), append(current.clueAnalyses(), clueAnalysis), mergeEvidence(current.evidenceReferences(), evidence),
                append(current.relationSets(), relationSet), append(current.auxiliaryAnalyses(), analysis),
                current.reviews(), impacts);
        return persist(actorRef, operation, idempotencyKey, fingerprint, next);
    }

    public EnforcementSnapshot reviseClue(
            String actorRef, String workItemId, int expectedVersion, String reason, String idempotencyKey) {
        String fingerprint = fingerprint("revise-clue", workItemId, expectedVersion, reason);
        String operation = "REVISE_CLUE";
        Optional<EnforcementSnapshot> replay = replay(actorRef, operation, idempotencyKey, fingerprint);
        if (replay.isPresent()) return replay.get();
        requireHuman(actorRef);
        EnforcementSnapshot current = owned(actorRef, workItemId).snapshot();
        ClueAnalysisVersion previous = latest(current.clueAnalyses(), "NOT_FOUND");
        if (previous.version() != expectedVersion) throw error("VERSION_CONFLICT");
        ClueAnalysisVersion revised = new ClueAnalysisVersion(
                ids.get(), expectedVersion + 1, previous.inputClueRef(), previous.subjectCandidates(), previous.suspectedFacts(),
                previous.timeCandidates(), previous.placeCandidates(), previous.evidenceMentions(), previous.missingItems(),
                previous.conflicts(), previous.priority(), previous.analysisId() + "@" + previous.version(), "HUMAN_REVISION",
                actorRef, now(), current.workItem().scopeLabel());
        EnforcementSnapshot next = new EnforcementSnapshot(current.workItem(), append(current.clueAnalyses(), revised),
                current.evidenceReferences(), current.relationSets(), current.auxiliaryAnalyses(), current.reviews(), current.reviewImpacts());
        return persist(actorRef, operation, idempotencyKey, fingerprint, next);
    }

    public EnforcementSnapshot reviewLatestAnalysis(
            String actorRef, String workItemId, int expectedVersion, String action, String reason, String idempotencyKey) {
        String fingerprint = fingerprint("review-analysis", workItemId, expectedVersion, action, reason);
        String operation = "REVIEW_ANALYSIS";
        Optional<EnforcementSnapshot> replay = replay(actorRef, operation, idempotencyKey, fingerprint);
        if (replay.isPresent()) return replay.get();
        requireHuman(actorRef);
        EnforcementSnapshot current = owned(actorRef, workItemId).snapshot();
        AuxiliaryAnalysisVersion analysis = latest(current.auxiliaryAnalyses(), "NOT_FOUND");
        if (analysis.version() != expectedVersion) throw error("VERSION_CONFLICT");
        HumanReviewRecord review;
        try {
            review = new HumanReviewRecord(ids.get(), "AUXILIARY_ANALYSIS", analysis.analysisResultId(), analysis.version(),
                    action, reason, actorRef, now(), reviewScope(current.workItem().scopeLabel()), true);
        } catch (IllegalArgumentException ex) {
            throw error(ex.getMessage().contains("reason") ? "REASON_REQUIRED" : "INVALID_ACTION");
        }
        EnforcementSnapshot next = new EnforcementSnapshot(current.workItem(), current.clueAnalyses(), current.evidenceReferences(),
                current.relationSets(), current.auxiliaryAnalyses(), append(current.reviews(), review), current.reviewImpacts());
        return persist(actorRef, operation, idempotencyKey, fingerprint, next);
    }

    public EnforcementSnapshot read(String actorRef, String workItemId) {
        EnforcementSnapshot snapshot = owned(actorRef, workItemId).snapshot();
        requireAccess(actorRef, "READ_CLUE", snapshot.workItem().clueRef().sourceId());
        for (EvidenceReference evidence : snapshot.evidenceReferences()) {
            requireAccess(actorRef, "READ_EVIDENCE", evidence.source().sourceId());
        }
        return snapshot;
    }

    public Object readVersion(String actorRef, String workItemId, String resource, String resourceId, int version) {
        EnforcementSnapshot snapshot = read(actorRef, workItemId);
        Optional<?> result = switch (resource) {
            case "clue-analyses" -> snapshot.clueAnalyses().stream()
                    .filter(value -> value.analysisId().equals(resourceId) && value.version() == version).findFirst();
            case "evidence-references" -> snapshot.evidenceReferences().stream()
                    .filter(value -> value.evidenceRefId().equals(resourceId) && value.version() == version).findFirst();
            case "relation-sets" -> snapshot.relationSets().stream()
                    .filter(value -> value.relationSetId().equals(resourceId) && value.version() == version).findFirst();
            case "auxiliary-analyses" -> snapshot.auxiliaryAnalyses().stream()
                    .filter(value -> value.analysisResultId().equals(resourceId) && value.version() == version).findFirst();
            default -> throw error("INVALID_INPUT");
        };
        return result.orElseThrow(() -> error("NOT_FOUND"));
    }

    public SourceLocator readSourceLink(String actorRef, String workItemId, String sourceLinkId) {
        EnforcementSnapshot snapshot = owned(actorRef, workItemId).snapshot();
        SourceLocator source = EnforcementSources.all(snapshot).stream().filter(value -> value.sourceId().equals(sourceLinkId))
                .findFirst().orElseThrow(() -> error("NOT_FOUND"));
        String action = "EVIDENCE".equals(EnforcementSources.kind(snapshot, sourceLinkId)) ? "READ_EVIDENCE" : "READ_CLUE";
        requireAccess(actorRef, action, source.sourceId());
        return source;
    }

    private EvidenceRelationSetVersion relationSet(
            EnforcementSnapshot current,
            ClueAnalysisVersion clue,
            List<EvidenceReference> evidence,
            List<String> unprocessed) {
        List<EvidenceRelationSetVersion.Relation> relations = evidence.stream()
                .filter(e -> "AVAILABLE".equals(e.availability()))
                .map(e -> {
                    EnforcementSourcePort.EvidenceData data = sources.findEvidence(e.source().sourceId()).orElseThrow();
                    return new EvidenceRelationSetVersion.Relation(data.issue(), ref(e), data.relationType(), data.relationReason(), data.source());
                }).toList();
        List<EvidenceRelationSetVersion.TimelineEntry> timeline = new ArrayList<>();
        for (EvidenceReference e : evidence) {
            if (e.occurrenceTime() != null) timeline.add(new EvidenceRelationSetVersion.TimelineEntry("OCCURRENCE", e.occurrenceTime(), e.occurrenceTime(), e.source()));
            if (e.collectionTime() != null) timeline.add(new EvidenceRelationSetVersion.TimelineEntry("COLLECTION", e.collectionTime(), e.collectionTime(), e.source()));
            if (e.uploadTime() != null) timeline.add(new EvidenceRelationSetVersion.TimelineEntry("UPLOAD", e.uploadTime(), e.uploadTime(), e.source()));
        }
        List<String> gaps = new ArrayList<>(clue.missingItems());
        if (relations.isEmpty()) gaps.add("NO_AVAILABLE_EVIDENCE");
        List<String> conflicts = clue.conflicts().stream().map(ClueAnalysisVersion.Conflict::field).toList();
        String completeness = !conflicts.isEmpty() ? "HAS_CONFLICTS" : (!gaps.isEmpty() || !unprocessed.isEmpty()) ? "HAS_GAPS" : "PENDING_HUMAN_REVIEW";
        return new EvidenceRelationSetVersion(ids.get(), current.relationSets().size() + 1,
                clue.analysisId() + "@" + clue.version(), sources.activeRule(), evidence.stream().map(this::ref).toList(),
                relations, timeline, gaps, conflicts, unprocessed, completeness, List.of(), now());
    }

    private AuxiliaryAnalysisVersion auxiliaryAnalysis(
            EnforcementSnapshot current,
            ClueAnalysisVersion clue,
            EvidenceRelationSetVersion relations,
            List<EvidenceReference> evidence,
            String clueId,
            String workScope) {
        Optional<SourceLocator> rightStatus = sources.historicalRightStatus(clueId);
        List<String> gaps = new ArrayList<>(relations.gaps());
        if (!relations.conflicts().isEmpty()) gaps.addAll(relations.conflicts());
        if (rightStatus.isEmpty()) gaps.add("HISTORICAL_RIGHT_STATUS_MISSING");
        List<AuxiliaryAnalysisVersion.AnalysisItem> items = new ArrayList<>();
        items.add(new AuxiliaryAnalysisVersion.AnalysisItem("CONFIRMED_FACT", "输入版本已固定并通过当前授权检查", List.of(clue.inputClueRef()), "仅为流程事实"));
        for (ClueAnalysisVersion.SourcedValue fact : clue.suspectedFacts()) {
            items.add(new AuxiliaryAnalysisVersion.AnalysisItem("PARTY_STATEMENT", fact.value(), List.of(fact.source()), "尚未经专业核实"));
        }
        for (EvidenceRelationSetVersion.Relation relation : relations.relations()) {
            items.add(new AuxiliaryAnalysisVersion.AnalysisItem("MACHINE_INFERENCE", relation.reason(), List.of(relation.source()), "已登记规则产生的候选关系，待人工复核"));
        }
        String outcome = gaps.isEmpty() && relations.unprocessedItems().isEmpty() ? "SUSPECTED_SUPPORT" : "MATERIALS_INSUFFICIENT";
        return new AuxiliaryAnalysisVersion(ids.get(), current.auxiliaryAnalyses().size() + 1,
                clue.analysisId() + "@" + clue.version(), relations.relationSetId() + "@" + relations.version(),
                evidence.stream().map(this::ref).toList(), sources.activeRule(), rightStatus.orElse(null), items, outcome,
                gaps, relations.unprocessedItems(), List.of(), latestAnalysisRef(current), true, now(), workScope);
    }

    private EvidenceReference toEvidence(
            EnforcementSourcePort.EvidenceData data,
            List<EvidenceReference> existing,
            List<EvidenceReference> pending,
            String workScope) {
        int version = java.util.stream.Stream.concat(existing.stream(), pending.stream())
                .filter(value -> value.source().sourceId().equals(data.source().sourceId()))
                .mapToInt(EvidenceReference::version)
                .max().orElse(0) + 1;
        return new EvidenceReference(ids.get(), version, data.source(), data.controlledResourceRef(), data.relatedClueId(), data.collector(),
                data.collectionMissingReason(), data.occurrenceTime(), data.collectionTime(), data.uploadTime(), data.availability(),
                data.originalRef(), data.derivedKind(), now(), workScope);
    }

    private EnforcementStore.StoredWork owned(String actorRef, String workItemId) {
        EnforcementStore.StoredWork stored = store.findByWorkItemId(workItemId).orElseThrow(() -> error("NOT_FOUND"));
        if (!stored.ownerRef().equals(actorRef) && !authorization.isTrustedHuman(actorRef)) throw error("UNAUTHORIZED");
        return stored;
    }

    private void requireAccess(String actorRef, String action, String resourceId) {
        if (!authorization.mayAccess(actorRef, action, resourceId)) throw error("UNAUTHORIZED");
    }

    private void requireHuman(String actorRef) {
        if (!authorization.isTrustedHuman(actorRef)) throw error("UNAUTHORIZED");
    }

    private Optional<EnforcementSnapshot> replay(String actorRef, String operation, String key, String fingerprint) {
        Optional<EnforcementStore.StoredResult> prior = store.findByIdempotency(actorRef, operation, key);
        if (prior.isEmpty()) return Optional.empty();
        if (!prior.get().fingerprint().equals(fingerprint)) throw error("IDEMPOTENCY_CONFLICT");
        return Optional.of(prior.get().snapshot());
    }

    private EnforcementSnapshot persist(String actorRef, String operation, String key, String fingerprint, EnforcementSnapshot snapshot) {
        try {
            return store.save(actorRef, operation, key, fingerprint, snapshot);
        } catch (EnforcementStore.IdempotencyConflict conflict) {
            throw error("IDEMPOTENCY_CONFLICT");
        }
    }

    private static String fingerprint(Object... values) { return String.join("|", Arrays.stream(values).map(String::valueOf).toList()); }
    private static <T> List<T> append(List<T> values, T value) { List<T> copy = new ArrayList<>(values); copy.add(value); return copy; }
    private static <T> T latest(List<T> values, String error) { if (values.isEmpty()) throw new EnforcementException(error); return values.get(values.size() - 1); }
    private List<EvidenceReference> mergeEvidence(List<EvidenceReference> existing, List<EvidenceReference> next) { List<EvidenceReference> copy = new ArrayList<>(existing); copy.addAll(next); return copy; }
    private String ref(EvidenceReference evidence) { return evidence.evidenceRefId() + "@" + evidence.version(); }
    private static String latestClueRef(EnforcementSnapshot snapshot) { return snapshot.clueAnalyses().isEmpty() ? null : snapshot.clueAnalyses().get(snapshot.clueAnalyses().size() - 1).analysisId() + "@" + snapshot.clueAnalyses().get(snapshot.clueAnalyses().size() - 1).version(); }
    private static String latestAnalysisRef(EnforcementSnapshot snapshot) { return snapshot.auxiliaryAnalyses().isEmpty() ? null : snapshot.auxiliaryAnalyses().get(snapshot.auxiliaryAnalyses().size() - 1).analysisResultId() + "@" + snapshot.auxiliaryAnalyses().get(snapshot.auxiliaryAnalyses().size() - 1).version(); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private String scope() { return "AUTHORIZED_LOCAL".equals(sources.dataMode()) ? "AUTHORIZED_LOCAL" : SYNTHETIC_SCOPE; }
    private static String reviewScope(String workScope) { return "AUTHORIZED_LOCAL".equals(workScope) ? "AUTHORIZED_LOCAL_REVIEW" : "SYNTHETIC_PROCESS_TEST"; }
    public List<String> limitations() { return List.of(scope(), "NOT_OFFICIAL_DECISION", sources.limitation()); }
    private static EnforcementException error(String code) { return new EnforcementException(code); }

    public static final class EnforcementException extends RuntimeException {
        private final String code;
        public EnforcementException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
