package cn.xinji.agentplatform.enforcement.domain;

import java.time.Instant;
import java.util.Set;

public record EnforcementWorkItem(
        String workItemId,
        SourceLocator clueRef,
        String organizationRef,
        Instant createdAt,
        String scopeLabel) {
    public EnforcementWorkItem {
        if (workItemId == null || workItemId.isBlank()) throw new IllegalArgumentException("workItemId is required");
        if (!Set.of("SYNTHETIC_V0", "AUTHORIZED_LOCAL").contains(scopeLabel)) {
            throw new IllegalArgumentException("Unsupported enforcement work scope");
        }
    }
}
