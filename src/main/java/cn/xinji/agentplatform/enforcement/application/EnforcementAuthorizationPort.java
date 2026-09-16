package cn.xinji.agentplatform.enforcement.application;

public interface EnforcementAuthorizationPort {
    boolean mayAccess(String actorRef, String action, String resourceId);
    boolean isTrustedHuman(String actorRef);
}
