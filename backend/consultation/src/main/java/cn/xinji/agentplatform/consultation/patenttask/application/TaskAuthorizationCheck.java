package cn.xinji.agentplatform.consultation.patenttask.application;

@FunctionalInterface
public interface TaskAuthorizationCheck {
    TaskAuthorizationDecision revalidate(ProtectedTaskAction action);
}
