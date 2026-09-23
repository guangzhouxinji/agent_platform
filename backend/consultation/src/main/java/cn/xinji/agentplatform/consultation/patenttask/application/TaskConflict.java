package cn.xinji.agentplatform.consultation.patenttask.application;

public record TaskConflict(
        Type type,
        String taskId,
        Long taskVersion,
        String objectId,
        Long expectedVersion,
        Long currentVersion,
        String suggestedRefreshTarget) {
    public enum Type { PAYLOAD, TASK_VERSION, OBJECT_VERSION, DEPENDENCY_VERSION, CONCURRENT_MODIFICATION }
}
