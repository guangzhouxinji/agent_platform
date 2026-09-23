package cn.xinji.agentplatform.consultation.patenttask.authorization;

import cn.xinji.agentplatform.consultation.patenttask.application.ProtectedTaskAction;

@FunctionalInterface
public interface AuthorizationPolicy {
    boolean permits(CurrentAuthorizationContext context, ProtectedTaskAction action,
                    ProtectedResourceAuthorizationRequirement requirement);
}
