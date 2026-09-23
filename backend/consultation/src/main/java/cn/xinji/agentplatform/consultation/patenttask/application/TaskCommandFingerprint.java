package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.RecoveryPoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class TaskCommandFingerprint {
    private TaskCommandFingerprint() {}

    static String forChange(TaskCommand command, TaskChange change) {
        StringBuilder value = metadata(command).append("|change:");
        change.subTaskChanges().stream().sorted(Comparator.comparing(SubTaskChange::subTaskId))
                .forEach(item -> append(value, item));
        appendMap(value, change.objectVersionUpdates());
        appendMap(value, change.dependencyVersionUpdates());
        return sha256(value.toString());
    }

    static String forSimple(TaskCommand command, String payloadReference) {
        return sha256(metadata(command).append("|payload:").append(nullSafe(payloadReference)).toString());
    }

    private static StringBuilder metadata(TaskCommand command) {
        StringBuilder value = new StringBuilder();
        value.append(command.commandIdentity()).append('|').append(command.idempotencyKey()).append('|')
                .append(command.type()).append('|').append(command.taskId()).append('|')
                .append(command.expectedTaskVersion());
        appendMap(value, command.referencedObjectVersions());
        appendMap(value, command.dependencyVersions());
        return value;
    }

    private static void append(StringBuilder value, SubTaskChange item) {
        value.append("|sub:").append(item.subTaskId()).append(':').append(item.expectedSubTaskVersion())
                .append(':').append(item.operation()).append(':').append(item.primary()).append(':')
                .append(item.reasonCode()).append(':').append(item.responsibilitySource()).append(':')
                .append(item.responsibilityReference());
        appendList(value, item.completionConditionReferences());
        appendRecovery(value, item.recoveryPoint());
    }

    private static void appendRecovery(StringBuilder value, RecoveryPoint point) {
        if (point == null) return;
        value.append("|recovery:").append(point.failedFromTaskVersion()).append(':')
                .append(point.subTaskId()).append(':').append(point.journeyStep()).append(':')
                .append(point.failedExecutionReference()).append(':').append(point.retryCommandReference());
        appendMap(value, point.contextVersionReferences());
        appendMap(value, point.dependencyVersions());
        appendList(value, point.committedArtifactReferences());
        appendList(value, point.incompleteActionReferences());
        appendList(value, point.sourceReferencesRequiringValidation());
    }

    private static void appendMap(StringBuilder value, Map<String, Long> map) {
        map.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> value.append("|").append(entry.getKey()).append('=').append(entry.getValue()));
    }

    private static void appendList(StringBuilder value, List<String> list) {
        list.stream().sorted().forEach(item -> value.append("|").append(nullSafe(item)));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String nullSafe(String value) { return value == null ? "<null>" : value; }
}
