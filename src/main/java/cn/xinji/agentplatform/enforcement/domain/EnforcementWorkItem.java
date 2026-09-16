package cn.xinji.agentplatform.enforcement.domain;

import java.time.Instant;

public record EnforcementWorkItem(
        String workItemId,
        SourceLocator clueRef,
        String organizationRef,
        Instant createdAt,
        String scopeLabel) {
    public EnforcementWorkItem {
        if (workItemId == null || workItemId.isBlank()) throw new IllegalArgumentException("workItemId is required");
        if (!"SYNTHETIC_V0".equals(scopeLabel)) throw new IllegalArgumentException("Only synthetic V0 work is allowed");
    }
}
