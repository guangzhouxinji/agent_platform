package cn.xinji.agentplatform.consultation.patenttask.authorization;

import cn.xinji.agentplatform.consultation.patenttask.application.ProtectedTaskAction;

import java.util.Optional;

@FunctionalInterface
public interface ProtectedResourceAuthorizationResolver {
    Optional<ProtectedResourceAuthorizationRequirement> resolve(ProtectedTaskAction action);
}
