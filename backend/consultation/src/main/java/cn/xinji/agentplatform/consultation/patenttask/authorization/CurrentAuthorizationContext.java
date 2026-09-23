package cn.xinji.agentplatform.consultation.patenttask.authorization;

import java.util.Objects;

public record CurrentAuthorizationContext(
        String contextReference,
        CurrentIdentity identity,
        DataScope dataScope,
        boolean valid) {
    public CurrentAuthorizationContext {
        if (contextReference == null || contextReference.isBlank()) {
            throw new IllegalArgumentException("contextReference is required");
        }
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(dataScope, "dataScope");
    }
}
