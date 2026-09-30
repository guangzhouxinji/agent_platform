package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Development-only, in-memory vertical flow. Neither this session nor its source is production persistence. */
public final class PatentConsultationFixtureFlow {
    private final PatentTaskCommandService tasks;
    private final PatentRetrievalService retrieval;
    private final PatentFixtureSourcePort source;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public PatentConsultationFixtureFlow(PatentTaskCommandService tasks, TaskAuthorizationCheck authorization, PatentFixtureSourcePort source) {
        this.tasks = Objects.requireNonNull(tasks);
        this.retrieval = new PatentRetrievalService(tasks, authorization, source);
        this.source = Objects.requireNonNull(source);
    }
    public View start(String description, String scope, List<String> featureTexts) {
        required(description); required(scope);
        if (featureTexts == null || featureTexts.stream().anyMatch(item -> item == null || item.isBlank()))
            throw new IllegalArgumentException("confirmed features are required");
        String id = UUID.randomUUID().toString();
        tasks.create(command(id, TaskCommandType.CREATE, 0), new TaskChange(List.of(new SubTaskChange(
                "patent", 0, SubTaskChange.Operation.ADD, true, null, SubTaskChange.ResponsibilitySource.RULE,
                "fixture:task-created", List.of(), null)), Map.of(), Map.of()));
        register(id, "scope", 1, PatentCorrectionAction.Type.SCOPE);
        if (featureTexts.isEmpty()) {
            PatentTaskSnapshot current = tasks.snapshot(id);
            tasks.continueTask(command(id, TaskCommandType.CONTINUE, current.version()), new TaskChange(List.of(
                    new SubTaskChange("patent", current.subTasks().get(0).version(),
                            SubTaskChange.Operation.WAIT_FOR_USER_INPUT, false, null,
                            SubTaskChange.ResponsibilitySource.RULE, "fixture:missing-key-feature", List.of(), null)),
                    Map.of(), Map.of()));
        }
        List<Feature> features = new ArrayList<>();
        for (int index = 0; index < featureTexts.size(); index++) {
            String featureId = "feature-" + (index + 1);
            register(id, featureId, 1, PatentCorrectionAction.Type.FEATURE);
            features.add(new Feature(featureId, 1, featureTexts.get(index).trim()));
        }
        sessions.put(id, new Session(description.trim(), scope.trim(), List.copyOf(features), null));
        return view(id);
    }
    public View supplementFeatures(String id, List<String> featureTexts) {
        Session session = session(id);
        if (!session.features().isEmpty() || featureTexts == null || featureTexts.isEmpty()
                || featureTexts.stream().anyMatch(item -> item == null || item.isBlank())) {
            throw new IllegalArgumentException("provide missing key features once");
        }
        List<Feature> features = new ArrayList<>();
        for (int index = 0; index < featureTexts.size(); index++) {
            String featureId = "feature-" + (index + 1);
            register(id, featureId, 1, PatentCorrectionAction.Type.FEATURE);
            features.add(new Feature(featureId, 1, featureTexts.get(index).trim()));
        }
        PatentTaskSnapshot current = tasks.snapshot(id);
        tasks.supplement(command(id, TaskCommandType.SUPPLEMENT, current.version()), new TaskChange(List.of(
                new SubTaskChange("patent", current.subTasks().get(0).version(), SubTaskChange.Operation.START,
                        false, null, SubTaskChange.ResponsibilitySource.USER, "fixture:user-features", List.of(), null)),
                Map.of(), Map.of()));
        sessions.put(id, new Session(session.description(), session.scope(), List.copyOf(features),
                session.scopeConfirmationReference()));
        return view(id);
    }
    public View search(String id, String query) {
        required(query);
        Session session = session(id);
        if (session.features().isEmpty()) throw new IllegalArgumentException("key features are still required");
        PatentTaskSnapshot current = tasks.snapshot(id);
        List<PatentRetrievalAction> previous = current.retrievalState().attempts().stream()
                .filter(action -> action.outcome() == PatentRetrievalAction.Outcome.RESULTS).toList();
        long resultVersion = previous.size() + 1;
        String scopeKey = current.correctionState().artifacts().values().stream()
                .filter(item -> item.id().equals("scope") && current.correctionState().usable(item.key()))
                .max(Comparator.comparingLong(PatentCorrectionAction.Artifact::version))
                .map(PatentCorrectionAction.Artifact::key).orElseThrow();
        retrieval.search(command(id, TaskCommandType.CONTINUE, current.version()),
                new PatentRetrievalService.Request("execution:" + UUID.randomUUID(), "main", session.scope(),
                        "fixture-keyword", query.trim(), "candidate-review",
                        session.scopeConfirmationReference(), "results", resultVersion,
                        resultVersion == 1 ? null : resultVersion - 1, scopeKey,
                        session.features().stream().map(Feature::key).toList()));
        PatentTaskSnapshot after = tasks.snapshot(id);
        if (after.resultFocus().resultSets().containsKey("results@" + resultVersion)) {
            tasks.continueTask(command(id, TaskCommandType.CONTINUE, after.version()),
                    new TaskChange(List.of(), Map.of(), Map.of(),
                            ResultFocusAction.onResult(ResultFocusAction.Kind.DELIVER, "main", "results", resultVersion)));
        }
        return view(id);
    }
    public View endWithoutEvidence(String id) {
        session(id);
        PatentTaskSnapshot current = tasks.snapshot(id);
        if (current.resultFocus().resultSets().values().stream().anyMatch(PatentResultSet::available)) {
            throw new IllegalArgumentException("available candidates require review before ending as no result");
        }
        tasks.continueTask(command(id, TaskCommandType.CONTINUE, current.version()), new TaskChange(List.of(
                new SubTaskChange("patent", current.subTasks().get(0).version(),
                        SubTaskChange.Operation.MARK_INSUFFICIENT_EVIDENCE, false,
                        current.retrievalState().attempts().stream().anyMatch(item ->
                                item.outcome() == PatentRetrievalAction.Outcome.NO_RESULT)
                                ? TaskReason.Code.NO_RESULT : TaskReason.Code.INSUFFICIENT_EVIDENCE,
                        SubTaskChange.ResponsibilitySource.USER, "fixture:user-ended", List.of(), null)),
                Map.of(), Map.of()));
        return view(id);
    }
    public View select(String id, long resultVersion, int ordinal) {
        session(id);
        PatentTaskSnapshot current = tasks.snapshot(id);
        tasks.select(command(id, TaskCommandType.SELECT, current.version()), new TaskChange(List.of(), Map.of(),
                Map.of(), ResultFocusAction.bind("main", "results", resultVersion, ordinal,
                        "selection:" + UUID.randomUUID())));
        return view(id);
    }
    public PatentFixtureDocument openSource(String id, String candidateId, String documentVersion) {
        session(id);
        PatentTaskSnapshot current = tasks.snapshot(id);
        boolean visible = current.resultFocus().resultSets().values().stream().anyMatch(set -> set.available()
                && set.candidates().stream().anyMatch(candidate -> candidate.candidateId().equals(candidateId)
                    && candidate.documentVersionReference().equals(documentVersion))
                && current.correctionState().usable("result:" + set.resultSetId() + "@" + set.version()));
        if (!visible) throw new IllegalArgumentException("source is not available in the current task");
        return source.document(candidateId, documentVersion);
    }
    public View compare(String id) {
        Session session = session(id);
        PatentTaskSnapshot current = tasks.snapshot(id);
        String selection = current.resultFocus().bindings().stream()
                .filter(binding -> binding.branchId().equals("main"))
                .reduce((first, second) -> second).map(ResultFocusState.ReferenceBinding::referenceId)
                .orElseThrow(() -> new IllegalArgumentException("select a document version first"));
        ResultFocusState.ReferenceBinding binding = current.resultFocus().usableBinding(selection);
        PatentResultSet set = current.resultFocus().resultSets().get(binding.resultSetId() + "@" + binding.resultSetVersion());
        if (!current.correctionState().usable("result:" + set.resultSetId() + "@" + set.version())) {
            throw new IllegalArgumentException("selected result has changed; search and select again");
        }
        PatentResultSet.Candidate candidate = set.candidates().stream()
                .filter(value -> value.candidateId().equals(binding.candidateId())).findFirst().orElseThrow();
        PatentFixtureDocument document = openSource(id, candidate.candidateId(), candidate.documentVersionReference());
        List<PatentComparisonAction.Item> items = new ArrayList<>();
        for (Feature feature : session.features()) {
            String evidenceId = "excerpt:" + UUID.randomUUID();
            var evidence = new PatentEvidenceAction.Evidence(evidenceId, 1, "main", document.sourceId(),
                    document.documentVersion(), document.locator(), feature.key(), true,
                    List.of("fixture excerpt; relationship not professionally verified"));
            tasks.continueTask(command(id, TaskCommandType.CONTINUE, tasks.snapshot(id).version()),
                    new TaskChange(List.of(), Map.of(), Map.of(), null, null,
                            PatentEvidenceAction.record(evidence)));
            items.add(new PatentComparisonAction.Item(feature.key(), evidence.key(),
                    PatentComparisonAction.Relationship.UNKNOWN, "Development excerpt for manual checking",
                    List.of("professional feature mapping"), "fixture:unreviewed"));
        }
        long nextVersion = current.comparisonState().versions().size() + 1;
        var comparison = new PatentComparisonAction("comparison", nextVersion, "main", "patent", selection,
                "technical-reference", items, List.of("fixture data", "not a legal or professional conclusion"));
        tasks.continueTask(command(id, TaskCommandType.CONTINUE, tasks.snapshot(id).version()),
                new TaskChange(List.of(), Map.of(), Map.of(), null, null, null, null, comparison));
        return view(id);
    }
    public View correctFeature(String id, String featureId, String replacement) {
        required(replacement);
        Session session = session(id);
        Feature before = session.features().stream().filter(item -> item.id().equals(featureId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown feature"));
        long next = before.version() + 1;
        var correction = new PatentCorrectionAction(PatentCorrectionAction.Kind.CORRECT,
                new PatentCorrectionAction.Artifact(featureId, next, PatentCorrectionAction.Type.FEATURE,
                        "main", List.of()), featureId, before.version(), "user corrected feature");
        tasks.correct(command(id, TaskCommandType.CORRECT, tasks.snapshot(id).version()),
                new TaskChange(List.of(), Map.of(), Map.of(), null, correction));
        List<Feature> updated = session.features().stream()
                .map(item -> item.id().equals(featureId) ? new Feature(featureId, next, replacement.trim()) : item).toList();
        sessions.put(id, new Session(session.description(), session.scope(), updated,
                session.scopeConfirmationReference()));
        return view(id);
    }
    public View changeScope(String id, String replacement) {
        required(replacement);
        Session session = session(id);
        PatentTaskSnapshot current = tasks.snapshot(id);
        TaskCommand confirmation = command(id, TaskCommandType.CONFIRM, current.version());
        retrieval.confirmScope(confirmation, "main",
                session.scope(), replacement.trim(), "user changed search scope");
        PatentTaskSnapshot confirmed = tasks.snapshot(id);
        long oldVersion = confirmed.correctionState().artifacts().values().stream()
                .filter(item -> item.id().equals("scope") && confirmed.correctionState().usable(item.key()))
                .mapToLong(PatentCorrectionAction.Artifact::version).max().orElseThrow();
        tasks.correct(command(id, TaskCommandType.CORRECT, confirmed.version()), new TaskChange(List.of(), Map.of(),
                Map.of(), null, new PatentCorrectionAction(PatentCorrectionAction.Kind.CORRECT,
                new PatentCorrectionAction.Artifact("scope", oldVersion + 1, PatentCorrectionAction.Type.SCOPE,
                        "main", List.of()), "scope", oldVersion, "user changed search scope")));
        sessions.put(id, new Session(session.description(), replacement.trim(), session.features(),
                confirmation.commandIdentity()));
        return view(id);
    }
    public View cancel(String id) {
        session(id);
        PatentTaskSnapshot current = tasks.snapshot(id);
        tasks.cancel(command(id, TaskCommandType.CANCEL, current.version()));
        return view(id);
    }
    public View resume(String id) {
        session(id);
        PatentTaskSnapshot current = tasks.snapshot(id);
        tasks.resume(command(id, TaskCommandType.RESUME, current.version()));
        return view(id);
    }
    public View prepareHandoff(String id) {
        Session session = session(id);
        PatentTaskSnapshot current = tasks.snapshot(id);
        var packet = new PatentHumanAction.Handoff("handoff:" + UUID.randomUUID(), 1, "main", "patent",
                current.version(), "technical-reference", session.features().stream().map(Feature::key).toList(),
                current.resultFocus().resultSets().keySet().stream().sorted().toList(),
                current.evidenceState().evidence().keySet().stream().sorted().toList(),
                current.comparisonState().versions().keySet().stream().sorted().toList(),
                List.of("professional review and real source unavailable"), session.scope(),
                "review technical comparison", "UNAVAILABLE");
        tasks.prepareHandoff(command(id, TaskCommandType.HUMAN_HANDOFF, current.version()),
                new TaskChange(List.of(), Map.of(), Map.of(), null, null, null, null, null,
                        PatentHumanAction.prepare(packet)));
        return view(id);
    }
    public View view(String id) {
        Session session = session(id);
        PatentTaskSnapshot snapshot = tasks.snapshot(id);
        List<CandidateView> candidates = snapshot.resultFocus().resultSets().values().stream()
                .sorted(Comparator.comparingLong(PatentResultSet::version).reversed())
                .flatMap(set -> set.candidates().stream().map(candidate -> {
                    PatentFixtureDocument document = source.document(candidate.candidateId(),
                            candidate.documentVersionReference());
                    return new CandidateView(set.version(), candidate.candidateId(), document.documentVersion(),
                            document.title(), document.sourceId(), document.locator(), set.available()
                            && snapshot.correctionState().usable("result:" + set.resultSetId() + "@" + set.version()));
                })).toList();
        PatentComparisonAction latest = snapshot.comparisonState().versions().values().stream()
                .max(Comparator.comparingLong(PatentComparisonAction::version)).orElse(null);
        Set<String> currentFeatureKeys = latest == null ? Set.of() : snapshot.comparisonState()
                .currentItems(latest.key(), snapshot.resultFocus(), snapshot.correctionState(),
                        snapshot.evidenceState()).stream().map(PatentComparisonAction.Item::featureKey)
                .collect(java.util.stream.Collectors.toSet());
        List<ComparisonView> comparisons = java.util.stream.Stream.ofNullable(latest)
                .flatMap(result -> result.items().stream().map(item -> new ComparisonView(item.featureKey(),
                        session.features().stream().filter(feature -> feature.key().equals(item.featureKey()))
                                .map(Feature::text).findFirst().orElse("Earlier feature version"),
                        item.relationship().name(), item.evidenceKey() == null ? null
                                : snapshot.evidenceState().evidence().get(item.evidenceKey()).locator(),
                        item.evidenceKey() == null ? null
                                : snapshot.evidenceState().evidence().get(item.evidenceKey()).sourceVersion(),
                        currentFeatureKeys.contains(item.featureKey())))).toList();
        String selectedCandidateReference = snapshot.resultFocus().bindings().stream()
                .filter(binding -> binding.branchId().equals("main"))
                .reduce((first, second) -> second)
                .filter(binding -> candidates.stream().anyMatch(candidate -> candidate.available()
                        && candidate.resultVersion() == binding.resultSetVersion()
                        && candidate.candidateId().equals(binding.candidateId())))
                .map(binding -> binding.resultSetVersion() + ":" + binding.candidateId()).orElse(null);
        return new View(id, snapshot.version(), snapshot.status().name(), session.description(), session.scope(),
                session.features(), candidates, selectedCandidateReference, comparisons, snapshot.retrievalState().attempts().stream()
                        .map(item -> item.outcome().name()).toList(),
                snapshot.humanState().prepared().isEmpty() ? "NOT_REQUESTED" : "UNAVAILABLE",
                List.of("Development fixture only", "Professional sufficiency not evaluated"));
    }
    private void register(String id, String artifactId, long version, PatentCorrectionAction.Type type) {
        tasks.continueTask(command(id, TaskCommandType.CONTINUE, tasks.snapshot(id).version()),
                new TaskChange(List.of(), Map.of(), Map.of(), null,
                        new PatentCorrectionAction(PatentCorrectionAction.Kind.REGISTER,
                                new PatentCorrectionAction.Artifact(artifactId, version, type, "main", List.of()),
                                null, 0, null)));
    }
    private TaskCommand command(String id, TaskCommandType type, long expected) {
        String identity = UUID.randomUUID().toString();
        return new TaskCommand(identity, identity, type, id, expected, Map.of(), Map.of(), null, Instant.now());
    }
    private Session session(String id) {
        Session session = sessions.get(id);
        if (session == null) throw new IllegalArgumentException("fixture task not found");
        return session;
    }
    private static void required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("input is required");
    }

    private record Session(String description, String scope, List<Feature> features, String scopeConfirmationReference) {}
    public record Feature(String id, long version, String text) {
        public String key() { return id + "@" + version; }
    }
    public record CandidateView(long resultVersion, String candidateId, String documentVersion, String title,
                                String sourceId, String locator, boolean available) {}
    public record ComparisonView(String featureKey, String feature, String relationship, String locator,
                                 String documentVersion, boolean current) {}
    public record View(String taskId, long taskVersion, String taskStatus, String description, String scope,
                       List<Feature> features, List<CandidateView> candidates, String selectedCandidateReference,
                       List<ComparisonView> comparisons,
                       List<String> retrievalOutcomes, String humanAvailability, List<String> limitations) {}
}
