package cn.xinji.agentplatform.consultation.patenttask.application;

public record ProtectedTaskAction(String action, String taskId, String untrustedClaimedIdentity,
                                  String untrustedClaimedScope) {
    public ProtectedTaskAction {
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action is required");
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
    }
}
