package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.*;

public record PatentEvidenceState(Map<String, PatentEvidenceAction.Evidence> evidence,
                                  Map<String, PatentEvidenceAction.EvidenceSet> sets,
                                  Map<String, PatentEvidenceAction.Assessment> assessments) {
    public PatentEvidenceState {
        evidence = Map.copyOf(evidence);
        sets = Map.copyOf(sets);
        assessments = Map.copyOf(assessments);
    }

    public static PatentEvidenceState empty() { return new PatentEvidenceState(Map.of(), Map.of(), Map.of()); }

    public PatentEvidenceState apply(PatentEvidenceAction action, PatentCorrectionState corrections) {
        Map<String, PatentEvidenceAction.Evidence> records = new LinkedHashMap<>(evidence);
        Map<String, PatentEvidenceAction.EvidenceSet> groups = new LinkedHashMap<>(sets);
        Map<String, PatentEvidenceAction.Assessment> evaluations = new LinkedHashMap<>(assessments);
        switch (action.kind()) {
            case RECORD_EVIDENCE -> {
                var item = action.evidence();
                if (records.containsKey(item.key()) || !corrections.usable(item.supportsArtifactKey()) ||
                        !corrections.artifacts().get(item.supportsArtifactKey()).branchId().equals(item.branchId())) {
                    throw new IllegalArgumentException("evidence must refer to an active artifact in its branch");
                }
                records.put(item.key(), item);
            }
            case RECORD_SET -> {
                var group = action.evidenceSet();
                if (groups.containsKey(group.key()) || group.evidenceKeys().isEmpty()) {
                    throw new IllegalArgumentException("evidence set must be new and nonempty");
                }
                for (String key : group.evidenceKeys()) {
                    var item = records.get(key);
                    if (item == null || !item.branchId().equals(group.branchId())) {
                        throw new IllegalArgumentException("evidence set refers to an unknown or foreign item");
                    }
                }
                groups.put(group.key(), group);
            }
            case ASSESS -> {
                var evaluation = action.assessment();
                var group = groups.get(evaluation.evidenceSetKey());
                if (evaluations.containsKey(evaluation.key()) || group == null
                        || !group.branchId().equals(evaluation.branchId())
                        || !corrections.usable(evaluation.targetArtifactKey())) {
                    throw new IllegalArgumentException("assessment requires an active target and evidence set");
                }
                if (group.evidenceKeys().stream().anyMatch(key -> !usableEvidence(key, corrections))) {
                    throw new IllegalArgumentException("assessment cannot consume unavailable evidence");
                }
                if (evaluation.matrixReference() == null || evaluation.matrixReference().isBlank()) {
                    if (evaluation.status() != PatentEvidenceAction.Status.NOT_EVALUABLE
                            && evaluation.status() != PatentEvidenceAction.Status.PENDING_EVIDENCE) {
                        throw new IllegalArgumentException("professional status requires an approved matrix");
                    }
                } else {
                    // An external approved rule adapter is required before any professional status is committed.
                    throw new IllegalArgumentException("approved evidence matrix adapter is not available");
                }
                evaluations.put(evaluation.key(), evaluation);
            }
        }
        return new PatentEvidenceState(records, groups, evaluations);
    }

    public boolean usableEvidence(String key, PatentCorrectionState corrections) {
        var item = evidence.get(key);
        return item != null && item.currentlyAccessible() && corrections.usable(item.supportsArtifactKey());
    }

    public boolean usableAssessment(String key, PatentCorrectionState corrections) {
        var evaluation = assessments.get(key);
        if (evaluation == null || !corrections.usable(evaluation.targetArtifactKey())) return false;
        var group = sets.get(evaluation.evidenceSetKey());
        return group != null && group.evidenceKeys().stream().allMatch(item -> usableEvidence(item, corrections));
    }
}
