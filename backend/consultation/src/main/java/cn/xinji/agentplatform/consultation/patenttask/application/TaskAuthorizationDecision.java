package cn.xinji.agentplatform.consultation.patenttask.application;

public record TaskAuthorizationDecision(boolean allowed, String contextReference, String denialReason) {
    public static TaskAuthorizationDecision allow(String contextReference) {
        if (contextReference == null || contextReference.isBlank()) {
            throw new IllegalArgumentException("authorization context reference is required");
        }
        return new TaskAuthorizationDecision(true, contextReference, "");
    }

    public static TaskAuthorizationDecision deny(String reason) {
        return new TaskAuthorizationDecision(false, "", reason == null ? "DENIED" : reason);
    }
}
