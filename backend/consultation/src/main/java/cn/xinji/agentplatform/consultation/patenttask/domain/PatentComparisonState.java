package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.*;

public record PatentComparisonState(Map<String, PatentComparisonAction> versions) {
    public PatentComparisonState { versions = Map.copyOf(versions); }

    public static PatentComparisonState empty() { return new PatentComparisonState(Map.of()); }

    public PatentComparisonState apply(PatentComparisonAction action, ResultFocusState focus,
                                       PatentCorrectionState corrections, PatentEvidenceState evidence) {
        if (versions.containsKey(action.key())) throw new IllegalArgumentException("comparison version already exists");
        ResultFocusState.ReferenceBinding selected = focus.usableBinding(action.selectionReference());
        if (!selected.branchId().equals(action.branchId())) {
            throw new IllegalArgumentException("comparison must stay in the selected branch");
        }
        PatentResultSet set = focus.resultSets().get(selected.resultSetId() + "@" + selected.resultSetVersion());
        if (set == null || !corrections.usable("result:" + set.resultSetId() + "@" + set.version())) {
            throw new IllegalArgumentException("selected result must have an active retrieval dependency");
        }
        String documentVersion = set.candidates().stream()
                .filter(candidate -> candidate.candidateId().equals(selected.candidateId()))
                .map(PatentResultSet.Candidate::documentVersionReference).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("selected candidate is not in snapshot"));
        for (var item : action.items()) {
            var feature = corrections.artifacts().get(item.featureKey());
            if (feature == null || feature.type() != PatentCorrectionAction.Type.FEATURE
                    || !feature.branchId().equals(action.branchId()) || !corrections.usable(item.featureKey())) {
                throw new IllegalArgumentException("comparison feature version is not active");
            }
            if (item.relationship() != PatentComparisonAction.Relationship.UNKNOWN) {
                throw new IllegalArgumentException("professional feature mapping is not approved for this fixture");
            }
            if (item.evidenceKey() != null && (!evidence.usableEvidence(item.evidenceKey(), corrections)
                    || !evidence.evidence().get(item.evidenceKey()).sourceVersion().equals(documentVersion))) {
                throw new IllegalArgumentException("comparison evidence is unavailable");
            }
        }
        Map<String, PatentComparisonAction> next = new LinkedHashMap<>(versions);
        next.put(action.key(), action);
        return new PatentComparisonState(next);
    }

    public List<PatentComparisonAction.Item> currentItems(String comparisonKey, ResultFocusState focus,
                                                           PatentCorrectionState corrections,
                                                           PatentEvidenceState evidence) {
        PatentComparisonAction comparison = versions.get(comparisonKey);
        if (comparison == null) throw new IllegalArgumentException("comparison not found");
        ResultFocusState.ReferenceBinding selected;
        try {
            selected = focus.usableBinding(comparison.selectionReference());
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            return List.of();
        }
        if (!corrections.usable("result:" + selected.resultSetId() + "@" + selected.resultSetVersion())) {
            return List.of();
        }
        return comparison.items().stream().filter(item -> corrections.usable(item.featureKey())
                && (item.evidenceKey() == null || evidence.usableEvidence(item.evidenceKey(), corrections))).toList();
    }
}
