package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Objects;

public record TaskEvent(
        String eventId,
        String commandIdentity,
        String eventType,
        long beforeVersion,
        long afterVersion,
        String authorizationContextReference,
        Map<String, Long> referencedObjectVersions,
        Map<String, Long> dependencyVersions,
        List<String> relatedArtifactReferences,
        List<String> preservedArtifactReferences,
        List<String> resumedActionReferences,
        String outcome,
        String conflictDetail,
        Instant occurredAt) {
    public TaskEvent {
        eventId = required(eventId, "eventId");
        commandIdentity = required(commandIdentity, "commandIdentity");
        eventType = required(eventType, "eventType");
        authorizationContextReference = required(authorizationContextReference, "authorizationContextReference");
        referencedObjectVersions = Map.copyOf(Objects.requireNonNull(referencedObjectVersions));
        dependencyVersions = Map.copyOf(Objects.requireNonNull(dependencyVersions, "dependencyVersions"));
        relatedArtifactReferences = List.copyOf(Objects.requireNonNull(relatedArtifactReferences));
        preservedArtifactReferences = List.copyOf(Objects.requireNonNull(preservedArtifactReferences));
        resumedActionReferences = List.copyOf(Objects.requireNonNull(resumedActionReferences));
        outcome = required(outcome, "outcome");
        conflictDetail = Objects.requireNonNullElse(conflictDetail, "");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
