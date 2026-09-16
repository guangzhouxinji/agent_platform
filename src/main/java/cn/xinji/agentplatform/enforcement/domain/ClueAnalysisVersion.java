package cn.xinji.agentplatform.enforcement.domain;

import java.time.Instant;
import java.util.List;

public record ClueAnalysisVersion(
        String analysisId,
        int version,
        SourceLocator inputClueRef,
        List<SubjectCandidate> subjectCandidates,
        List<SourcedValue> suspectedFacts,
        List<SourcedValue> timeCandidates,
        List<SourcedValue> placeCandidates,
        List<SourcedValue> evidenceMentions,
        List<String> missingItems,
        List<Conflict> conflicts,
        String priority,
        String previousVersionRef,
        String revisionKind,
        String actorRef,
        Instant createdAt,
        String scopeLabel) {

    public ClueAnalysisVersion {
        subjectCandidates = List.copyOf(subjectCandidates);
        suspectedFacts = List.copyOf(suspectedFacts);
        timeCandidates = List.copyOf(timeCandidates);
        placeCandidates = List.copyOf(placeCandidates);
        evidenceMentions = List.copyOf(evidenceMentions);
        missingItems = List.copyOf(missingItems);
        conflicts = List.copyOf(conflicts);
        if (!"TO_BE_EVALUATED".equals(priority)) throw new IllegalArgumentException("Synthetic V0 priority must remain unevaluated");
    }

    public record SubjectCandidate(String roleAsStated, String name, String creditCode, SourceLocator source) {}
    public record SourcedValue(String value, String normalizedValue, String timeKind, SourceLocator source) {}
    public record Conflict(String field, List<SourcedValue> candidates) {
        public Conflict { candidates = List.copyOf(candidates); }
    }
}
