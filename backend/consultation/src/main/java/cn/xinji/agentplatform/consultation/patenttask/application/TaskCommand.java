package cn.xinji.agentplatform.consultation.patenttask.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record TaskCommand(
        String commandIdentity,
        String idempotencyKey,
        String untrustedPayloadFingerprint,
        TaskCommandType type,
        String taskId,
        long expectedTaskVersion,
        Map<String, Long> referencedObjectVersions,
        Map<String, Long> dependencyVersions,
        String untrustedClaimedIdentity,
        Instant occurredAt) {
    public TaskCommand {
        commandIdentity = required(commandIdentity, "commandIdentity");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(type, "type");
        taskId = required(taskId, "taskId");
        if (type == TaskCommandType.CREATE && expectedTaskVersion != 0) {
            throw new IllegalArgumentException("create expected version must be zero");
        }
        if (type != TaskCommandType.CREATE && expectedTaskVersion < 1) {
            throw new IllegalArgumentException("expected version must be positive");
        }
        referencedObjectVersions = Map.copyOf(Objects.requireNonNull(referencedObjectVersions));
        dependencyVersions = Map.copyOf(Objects.requireNonNull(dependencyVersions));
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public TaskCommand(String commandIdentity, String idempotencyKey, TaskCommandType type, String taskId,
                       long expectedTaskVersion, Map<String, Long> referencedObjectVersions,
                       Map<String, Long> dependencyVersions, String untrustedClaimedIdentity, Instant occurredAt) {
        this(commandIdentity, idempotencyKey, null, type, taskId, expectedTaskVersion,
                referencedObjectVersions, dependencyVersions, untrustedClaimedIdentity, occurredAt);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
