package cn.xinji.agentplatform.consultation.patenttask.authorization;

import java.util.Optional;

@FunctionalInterface
public interface CurrentAuthorizationContextProvider {
    Optional<CurrentAuthorizationContext> current();
}
