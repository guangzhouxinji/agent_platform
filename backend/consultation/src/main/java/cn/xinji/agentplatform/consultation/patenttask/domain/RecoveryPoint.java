package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RecoveryPoint(
        long failedFromTaskVersion,
        String subTaskId,
        String journeyStep,
        Map<String, Long> contextVersionReferences,
        List<String> committedArtifactReferences,
        List<String> incompleteActionReferences,
        String failedExecutionReference,
        String retryCommandReference,
        Map<String, Long> dependencyVersions,
        List<String> sourceReferencesRequiringValidation) {
    public RecoveryPoint {
        if (failedFromTaskVersion < 1) throw new IllegalArgumentException("failedFromTaskVersion must be positive");
        subTaskId = requireText(subTaskId, "subTaskId");
        journeyStep = requireText(journeyStep, "journeyStep");
        contextVersionReferences = Map.copyOf(Objects.requireNonNull(contextVersionReferences));
        committedArtifactReferences = List.copyOf(Objects.requireNonNull(committedArtifactReferences));
        incompleteActionReferences = List.copyOf(Objects.requireNonNull(incompleteActionReferences));
        if (incompleteActionReferences.isEmpty()) {
            throw new IllegalArgumentException("at least one incomplete action is required");
        }
        failedExecutionReference = requireText(failedExecutionReference, "failedExecutionReference");
        retryCommandReference = requireText(retryCommandReference, "retryCommandReference");
        dependencyVersions = Map.copyOf(Objects.requireNonNull(dependencyVersions, "dependencyVersions"));
        sourceReferencesRequiringValidation = List.copyOf(
                Objects.requireNonNull(sourceReferencesRequiringValidation));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
