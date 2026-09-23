package cn.xinji.agentplatform.consultation.patenttask.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Arrival audit is independent of task snapshots and therefore records rejected commands without a task version. */
public record TaskCommandReceipt(
        String receiptId,
        String taskId,
        String commandIdentity,
        String idempotencyKey,
        String payloadFingerprint,
        Instant receivedAt,
        long expectedTaskVersion,
        Long actualTaskVersion,
        Map<String, Long> referencedObjectVersions,
        Map<String, Long> dependencyVersions,
        String authorizationResult,
        Outcome outcome,
        String detail) {
    public TaskCommandReceipt {
        receiptId = required(receiptId, "receiptId");
        taskId = required(taskId, "taskId");
        commandIdentity = required(commandIdentity, "commandIdentity");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        payloadFingerprint = required(payloadFingerprint, "payloadFingerprint");
        Objects.requireNonNull(receivedAt, "receivedAt");
        referencedObjectVersions = Map.copyOf(Objects.requireNonNull(referencedObjectVersions));
        dependencyVersions = Map.copyOf(Objects.requireNonNull(dependencyVersions));
        authorizationResult = Objects.requireNonNullElse(authorizationResult, "NOT_EVALUATED");
        Objects.requireNonNull(outcome, "outcome");
        detail = Objects.requireNonNullElse(detail, "");
    }

    public TaskCommandReceipt complete(Long actualVersion, String authorization, Outcome result, String resultDetail) {
        return new TaskCommandReceipt(receiptId, taskId, commandIdentity, idempotencyKey, payloadFingerprint,
                receivedAt, expectedTaskVersion, actualVersion, referencedObjectVersions, dependencyVersions,
                authorization, result, resultDetail);
    }

    public enum Outcome {
        RECEIVED, APPLIED, REPLAYED, REJECTED_PAYLOAD_CONFLICT, REJECTED_STALE_VERSION,
        REJECTED_OBJECT_VERSION, REJECTED_DEPENDENCY_VERSION, REJECTED_AUTHORIZATION,
        REJECTED_CANCELLED, REJECTED_INVALID, REJECTED_NOT_FOUND, REJECTED_ALREADY_EXISTS
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
