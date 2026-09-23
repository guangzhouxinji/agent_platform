package cn.xinji.agentplatform.enforcement.application;

import cn.xinji.agentplatform.enforcement.domain.ClueAnalysisVersion;
import cn.xinji.agentplatform.enforcement.domain.SourceLocator;
import java.util.List;
import java.util.Optional;

public interface EnforcementSourcePort {
    Optional<ClueData> findClue(String clueId, String version);
    Optional<EvidenceData> findEvidence(String evidenceId);
    SourceLocator activeRule();
    Optional<SourceLocator> historicalRightStatus(String clueId);
    default List<ClueData> listClues() { return List.of(); }
    default List<EvidenceData> listEvidence() { return List.of(); }
    default String dataMode() { return "UNAVAILABLE"; }
    default String catalogVersion() { return "unavailable"; }
    default String limitation() { return "当前未配置执法输入来源。"; }

    record ClueData(
            SourceLocator source,
            List<ClueAnalysisVersion.SubjectCandidate> subjects,
            List<ClueAnalysisVersion.SourcedValue> facts,
            List<ClueAnalysisVersion.SourcedValue> times,
            List<ClueAnalysisVersion.SourcedValue> places,
            List<ClueAnalysisVersion.SourcedValue> evidenceMentions,
            List<String> missingItems,
            List<ClueAnalysisVersion.Conflict> conflicts) {
        public ClueData {
            subjects = List.copyOf(subjects); facts = List.copyOf(facts); times = List.copyOf(times);
            places = List.copyOf(places); evidenceMentions = List.copyOf(evidenceMentions);
            missingItems = List.copyOf(missingItems); conflicts = List.copyOf(conflicts);
        }
    }

    record EvidenceData(
            SourceLocator source,
            String controlledResourceRef,
            String relatedClueId,
            String collector,
            String collectionMissingReason,
            String occurrenceTime,
            String collectionTime,
            String uploadTime,
            String availability,
            String issue,
            String relationType,
            String relationReason,
            String originalRef,
            String derivedKind) {}
}
